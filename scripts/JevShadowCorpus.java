import cn.lwx.lwxaiagent.entity.GuardrailRule;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.harness.governance.JevSelfHarmSignal;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.infrastructure.ai.JevClient;
import cn.lwx.lwxaiagent.infrastructure.ai.JevProperties;
import cn.lwx.lwxaiagent.mapper.GuardrailRuleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 影子观测语料跑批（docs/phase7-jev-shadow）。
 *
 * <p><b>复用产线类，不重写任何判定逻辑</b>：</p>
 * <ul>
 *   <li>解密 → 真实的 {@link EncryptionService}（不自己写一份 AES-GCM）；</li>
 *   <li>词典 → 真实的 {@link GuardrailRuleService}（只把它的数据访问换成直读 JDBC，
 *       匹配语义仍是产线那份 KEYWORD 包含 / REGEX find / 取最高级）；</li>
 *   <li>第二信号 → 真实的 {@link JevSelfHarmSignal#judge(String)}，问句与阈值都用产线的。</li>
 * </ul>
 *
 * <p><b>两段式取样（2026-09-20 第一轮教训）</b>：首轮纯随机抽 800 条，Jev 概率 max 只有 0.04、
 * 词典 L3 命中 0 条——真实对话里危机表达的基率极低，随机抽样碰不到，也就测不出阈值附近的行为。
 * 改为：先对<b>全量</b>跑词典（本地、零成本），再把「词典有信号的尾部（level ≥ minLevel）」与
 * 「分层随机样本」合并送 Jev。只有随机样本那一支可以用来估<b>误报率</b>，
 * 尾部样本是刻意加权的，不能混进分母。</p>
 *
 * <p>参数：{@code tsv out perStratum threads [minLevel] [maxTail]}</p>
 */
public final class JevShadowCorpus {

    private static final String DB_URL =
            "jdbc:mysql://localhost:3306/agentdb?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                    + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "123456";

    private record Row(long id, String conv, String user, String cipher, String stratum) {}

    private record Scored(Row row, String text, boolean decrypted, int dictLevel, String dictRule) {}

    private record Result(Scored s, String selectedBy, Double probability, Boolean exceeds, long ms, String error) {}

    public static void main(String[] args) throws Exception {
        Path tsv = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int perStratum = args.length > 2 ? Integer.parseInt(args[2]) : 400;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int minLevel = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        int maxTail = args.length > 5 ? Integer.parseInt(args[5]) : 600;

        // ---------- 1. 读语料 ----------
        List<Row> all = readTsv(tsv);
        System.out.println("[1] 语料总行数 = " + all.size());

        // ---------- 2. 解密 + 词典（全量，本地，零外部调用） ----------
        EncryptionService enc = new EncryptionService(System.getenv().getOrDefault("APP_MESSAGE_KEY", ""), true);
        GuardrailRuleService dict = realDictionary();
        List<Scored> scored = new ArrayList<>(all.size());
        long decFail = 0;
        Map<Integer, Integer> levelHist = new TreeMap<>();
        for (Row r : all) {
            String text = enc.decrypt(r.cipher(), r.user());
            boolean ok = !text.equals(r.cipher());
            if (!ok) decFail++;
            int level = -1;
            String rule = null;
            try {
                var v = dict.check(text);
                level = v.level();
                rule = v.ruleId();
            } catch (RuntimeException e) {
                level = -2;
            }
            levelHist.merge(level, 1, Integer::sum);
            scored.add(new Scored(r, text, ok, level, rule));
        }
        System.out.println("[2] 解密失败(原样返回) = " + decFail);
        System.out.println("[2] 全量词典等级分布 = " + levelHist);

        // ---------- 3. 取样：词典尾部 + 分层随机 ----------
        // 尾部 = 词典 level 落在 [minLevel, 3) 的消息：词典判 L3 的由 dict_l3 对照组单独负责，
        // 若这里不过滤上界，L3 会被 tail 与 dictL3 各取一次 → 输出里出现重复行（首版就踩了这个）。
        List<Scored> tail = scored.stream()
                .filter(s -> s.decrypted() && s.dictLevel() >= minLevel && s.dictLevel() < 3)
                .sorted(Comparator.comparingInt((Scored s) -> -s.dictLevel()).thenComparingLong(s -> s.row().id()))
                .limit(maxTail).toList();
        java.util.Set<Long> tailIds = new java.util.HashSet<>();
        tail.forEach(s -> tailIds.add(s.row().id()));
        List<Scored> random = sample(scored.stream().filter(s -> s.decrypted() && !tailIds.contains(s.row().id())).toList(), perStratum);

        // 阳性对照：词典 L3 的消息在产线上不会走到 Jev 分支，这里**照样判一次**，
        // 只用来回答"真实危机句上 Jev 给多少分"——验证花园里标出的 0.6 阈值在真实数据上是否成立。
        // 它们单独成组（selected_by=dict_l3），既不算误报率分母，也不算尾部口径。
        List<Scored> dictL3 = scored.stream()
                .filter(s -> s.decrypted() && s.dictLevel() >= 3)
                .sorted(Comparator.comparingLong(s -> s.row().id())).toList();

        List<Object[]> targets = new ArrayList<>();
        tail.forEach(s -> targets.add(new Object[]{s, "tail"}));
        random.forEach(s -> targets.add(new Object[]{s, "random"}));
        dictL3.forEach(s -> targets.add(new Object[]{s, "dict_l3"}));
        System.out.println("[3] 送 Jev：尾部(词典>=" + minLevel + ") " + tail.size() + " + 随机 " + random.size()
                + " + 词典L3对照 " + dictL3.size() + " = " + targets.size());

        // ---------- 4. 第二信号（真实 JevSelfHarmSignal，SHADOW 模式） ----------
        String key = System.getenv("JEV_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("JEV_KEY 未设置");
        JevProperties props = new JevProperties();
        props.setEnabled(true);
        props.setApiKey(key);
        props.setTimeoutMs(8000);
        props.getGuardrail().setMode(JevProperties.Mode.SHADOW);
        props.getGuardrail().setMinProbability(0.6);
        JevSelfHarmSignal signal = new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
        System.out.println("[4] Jev 第二信号: mode=" + signal.mode() + " threshold=" + signal.threshold()
                + " available=" + signal.enabled());

        AtomicInteger jevCalls = new AtomicInteger();
        AtomicInteger jevFail = new AtomicInteger();
        Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Result>> futures = new ArrayList<>();
        for (Object[] t : targets) {
            Scored s = (Scored) t[0];
            String selectedBy = (String) t[1];
            futures.add(pool.submit(() -> {
                Double probability = null;
                Boolean exceeds = null;
                String error = null;
                long ms = 0;
                if (true) {
                    long t0 = System.nanoTime();
                    try {
                        var risk = signal.judge(s.text());
                        ms = (System.nanoTime() - t0) / 1_000_000;
                        jevCalls.incrementAndGet();
                        if (risk.isPresent()) {
                            probability = risk.get().probability();
                            exceeds = risk.get().exceedsThreshold();
                        } else {
                            error = "no-result";
                            jevFail.incrementAndGet();
                            failures.computeIfAbsent("no-result", k -> new AtomicInteger()).incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        ms = (System.nanoTime() - t0) / 1_000_000;
                        jevCalls.incrementAndGet();
                        error = e.getClass().getSimpleName();
                        jevFail.incrementAndGet();
                        failures.computeIfAbsent(error, k -> new AtomicInteger()).incrementAndGet();
                    }
                }
                return new Result(s, s.dictLevel() >= 3 ? "dict_l3" : selectedBy, probability, exceeds, ms, error);
            }));
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.MINUTES);

        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) results.add(f.get());
        results.sort(Comparator.comparingLong(r -> r.s().row().id()));

        // ---------- 5. 输出 ----------
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("{\"kind\":\"corpus_summary\",\"rows\":" + all.size()
                    + ",\"decrypt_failed\":" + decFail
                    + ",\"dictionary_level_histogram\":" + levelHistJson(levelHist) + "}");
            for (Result r : results) w.println(toJson(r));
        }
        System.out.println("[5] 写出 " + out.toAbsolutePath());
        System.out.println("    Jev 调用次数 = " + jevCalls.get() + "  失败 = " + jevFail.get() + " " + failures);
    }

    /** 真实词典服务 + 直读 JDBC 的数据访问（匹配语义仍是产线那份）。 */
    private static GuardrailRuleService realDictionary() throws Exception {
        Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        InvocationHandler handler = (proxy, method, a) -> {
            if ("selectList".equals(method.getName())) {
                List<GuardrailRule> rules = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(
                             "select rule_id, level, pattern_type, pattern, enabled from guardrail_rule where enabled=1")) {
                    while (rs.next()) {
                        GuardrailRule r = new GuardrailRule();
                        r.setRuleId(rs.getString(1));
                        r.setLevel(rs.getInt(2));
                        r.setPatternType(rs.getString(3));
                        r.setPattern(rs.getString(4));
                        r.setEnabled(rs.getBoolean(5));
                        rules.add(r);
                    }
                }
                return rules;
            }
            Class<?> rt = method.getReturnType();
            if (rt == int.class) return 0;
            if (rt == boolean.class) return false;
            if (rt == long.class) return 0L;
            return null;
        };
        GuardrailRuleMapper mapper = (GuardrailRuleMapper) Proxy.newProxyInstance(
                GuardrailRuleMapper.class.getClassLoader(), new Class<?>[]{GuardrailRuleMapper.class}, handler);
        GuardrailRuleService service = new GuardrailRuleService(mapper);
        // load() 是包级私有（@PostConstruct），这里反射调用，避免为了探针改动产线可见性
        Method load = GuardrailRuleService.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(service);
        System.out.println("[2] 词典规则已加载（真实 GuardrailRuleService）");
        return service;
    }

    private static List<Row> readTsv(Path tsv) throws Exception {
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("id\t")) continue;
            String[] p = line.split("\t", 4);
            if (p.length < 4) continue;
            String user = p[2];
            String stratum = (user.startsWith("verify_") || user.startsWith("aev_")) ? "synthetic" : "organic";
            rows.add(new Row(Long.parseLong(p[0]), p[1], user, p[3], stratum));
        }
        return rows;
    }

    /**
     * 分层抽样：每会话最多 1 条、每用户最多 2 条。
     *
     * <p>分层是必要的：实测 91% 的 USER 消息来自 {@code verify_*} / {@code aev_*} 脚本账号，
     * 两者混在一起报一个数字会掩盖差异。固定随机种子，可复现。</p>
     */
    private static List<Scored> sample(List<Scored> pool, int perStratum) {
        List<Scored> picked = new ArrayList<>();
        for (String stratum : List.of("synthetic", "organic")) {
            List<Scored> bucket = new ArrayList<>(pool.stream().filter(s -> stratum.equals(s.row().stratum())).toList());
            Collections.shuffle(bucket, new Random(20260920L));
            Map<String, Integer> perConv = new HashMap<>();
            Map<String, Integer> perUser = new HashMap<>();
            int n = 0;
            for (Scored s : bucket) {
                if (n >= perStratum) break;
                if (perConv.getOrDefault(s.row().conv(), 0) >= 1) continue;
                if (perUser.getOrDefault(s.row().user(), 0) >= 2) continue;
                perConv.merge(s.row().conv(), 1, Integer::sum);
                perUser.merge(s.row().user(), 1, Integer::sum);
                picked.add(s);
                n++;
            }
        }
        return picked;
    }

    private static String levelHistJson(Map<Integer, Integer> hist) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (var e : hist.entrySet()) {
            if (!first) b.append(',');
            b.append('"').append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        return b.append('}').toString();
    }

    private static String esc(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String toJson(Result r) {
        return "{\"id\":" + r.s().row().id()
                + ",\"stratum\":" + esc(r.s().row().stratum())
                + ",\"selected_by\":" + esc(r.selectedBy())
                + ",\"user\":" + esc(r.s().row().user())
                + ",\"decrypted\":" + r.s().decrypted()
                + ",\"dict_level\":" + r.s().dictLevel()
                + ",\"dict_rule\":" + esc(r.s().dictRule())
                + ",\"probability\":" + (r.probability() == null ? "null" : r.probability())
                + ",\"exceeds\":" + (r.exceeds() == null ? "null" : r.exceeds())
                + ",\"ms\":" + r.ms()
                + ",\"error\":" + esc(r.error())
                + ",\"text\":" + esc(r.s().text()) + "}";
    }

    private JevShadowCorpus() {}
}
