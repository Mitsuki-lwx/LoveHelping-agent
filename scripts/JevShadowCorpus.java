import cn.lwx.lwxaiagent.entity.GuardrailRule;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.harness.governance.JevSelfHarmSignal;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.infrastructure.ai.JevClient;
import cn.lwx.lwxaiagent.infrastructure.ai.JevProperties;
import cn.lwx.lwxaiagent.mapper.GuardrailRuleMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 影子观测 / 生产模拟用的离线判定探针（docs/phase7-jev-shadow、docs/phase7-prod-sim）。
 *
 * <p><b>复用产线类，不重写任何判定逻辑</b>：</p>
 * <ul>
 *   <li>解密 → 真实的 {@link EncryptionService}（不自己写一份 AES-GCM）；</li>
 *   <li>词典 → 真实的 {@link GuardrailRuleService}（只把它的数据访问换成直读 JDBC，
 *       匹配语义仍是产线那份 KEYWORD 包含 / REGEX find / 取最高级）；</li>
 *   <li>第二信号 → 真实的 {@link JevSelfHarmSignal#judge(String)}，问句与阈值都取自产线配置。</li>
 * </ul>
 *
 * <p>三种用法（第一个参数是模式）：</p>
 * <pre>
 *   # ① 影子观测：全量词典 + 尾部加权 + 分层随机（原行为）
 *   JevShadowCorpus &lt;corpus.tsv&gt; &lt;out.jsonl&gt; [perStratum=400] [threads=4] [minLevel=2] [maxTail=600]
 *
 *   # ② 从真实语料里"挑"某一类（用于构造误报高危集）：只 dump 明文，不调 Jev
 *   JevShadowCorpus --select &lt;corpus.tsv&gt; &lt;out.jsonl&gt; &lt;kw1,kw2,...&gt; [limit=200]
 *
 *   # ③ 对一份明文语料跑产线判定（未用于调参的 hold-out 集走这里）
 *   JevShadowCorpus --judge &lt;in.jsonl&gt; &lt;out.jsonl&gt; [threads=4]
 *   #   in.jsonl 每行： {"text":"...","group":"crisis_holdout","label":"positive"}
 * </pre>
 *
 * <p>明文只落 `outputs/`（已 gitignore），仓库里只放统计量。</p>
 */
public final class JevShadowCorpus {

    private static final String DB_URL =
            "jdbc:mysql://localhost:3306/agentdb?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                    + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "123456";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Row(long id, String conv, String user, String cipher, String stratum) {}

    private record Scored(Row row, String text, boolean decrypted, int dictLevel, String dictRule) {}

    private record Result(Scored s, String selectedBy, Double probability, Boolean exceeds, long ms, String error) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--select".equals(args[0])) {
            select(args);
        } else if (args.length > 0 && "--judge".equals(args[0])) {
            judgePlain(args);
        } else {
            corpus(args);
        }
    }

    // ==================== ① 影子观测（原行为） ====================

    private static void corpus(String[] args) throws Exception {
        Path tsv = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int perStratum = args.length > 2 ? Integer.parseInt(args[2]) : 400;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        int minLevel = args.length > 4 ? Integer.parseInt(args[4]) : 2;
        int maxTail = args.length > 5 ? Integer.parseInt(args[5]) : 600;

        List<Row> all = readTsv(tsv);
        System.out.println("[1] 语料总行数 = " + all.size());

        List<Scored> scored = scoreAll(all);

        // 尾部 = 词典 level 落在 [minLevel, 3) 的消息：词典判 L3 的由 dict_l3 对照组单独负责，
        // 若这里不过滤上界，L3 会被 tail 与 dictL3 各取一次 → 输出里出现重复行（首版就踩了这个）。
        List<Scored> tail = scored.stream()
                .filter(s -> s.decrypted() && s.dictLevel() >= minLevel && s.dictLevel() < 3)
                .sorted(Comparator.comparingInt((Scored s) -> -s.dictLevel()).thenComparingLong(s -> s.row().id()))
                .limit(maxTail).toList();
        Set<Long> tailIds = new java.util.HashSet<>();
        tail.forEach(s -> tailIds.add(s.row().id()));
        // 三组必须构成**分划**：random 排除 tail 与 L3。
        // （上一轮 L3 只有 7/53028，恰好没被随机抽中——那是运气不是设计。）
        List<Scored> random = sample(scored.stream()
                .filter(s -> s.decrypted() && s.dictLevel() < 3 && !tailIds.contains(s.row().id()))
                .toList(), perStratum);

        // 阳性对照：词典 L3 的消息在产线上不会走到 Jev 分支，这里照样判一次，
        // 只用来回答"真实危机句上 Jev 给多少分"。它们单独成组，不进任何分母。
        List<Scored> dictL3 = scored.stream()
                .filter(s -> s.decrypted() && s.dictLevel() >= 3)
                .sorted(Comparator.comparingLong(s -> s.row().id())).toList();

        List<Object[]> targets = new ArrayList<>();
        tail.forEach(s -> targets.add(new Object[]{s, "tail"}));
        random.forEach(s -> targets.add(new Object[]{s, "random"}));
        dictL3.forEach(s -> targets.add(new Object[]{s, "dict_l3"}));
        System.out.println("[3] 送 Jev：尾部(词典>=" + minLevel + ") " + tail.size() + " + 随机 " + random.size()
                + " + 词典L3对照 " + dictL3.size() + " = " + targets.size());

        List<Result> results = runJev(targets, threads);

        Map<Integer, Integer> levelHist = new TreeMap<>();
        long decFail = 0;
        for (Scored s : scored) {
            if (!s.decrypted()) decFail++;
            levelHist.merge(s.dictLevel(), 1, Integer::sum);
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("{\"kind\":\"corpus_summary\",\"rows\":" + all.size()
                    + ",\"decrypt_failed\":" + decFail
                    + ",\"dictionary_level_histogram\":" + levelHistJson(levelHist) + "}");
            for (Result r : results) w.println(toJson(r));
        }
        System.out.println("[5] 写出 " + out.toAbsolutePath());
    }

    // ==================== ② 从真实语料挑一类（误报高危集） ====================

    /**
     * 挑出"含情绪词、但词典没判 L3"的消息。
     *
     * <p>为什么需要：随机抽样里绝大多数是"你好""怎么回复"这类，**压不到"情绪强烈但不自伤"这个区域**，
     * 而那恰恰是误报最可能发生的地方（分手、被甩、崩溃大哭）。这里刻意把这一块抽出来送 Jev。</p>
     */
    private static void select(String[] args) throws Exception {
        Path tsv = Path.of(args[1]);
        Path out = Path.of(args[2]);
        String[] keywords = args[3].split(",");
        int limit = args.length > 4 ? Integer.parseInt(args[4]) : 200;

        List<Row> all = readTsv(tsv);
        List<Scored> scored = scoreAll(all);
        Set<String> seen = new LinkedHashSet<>();
        List<Scored> picked = new ArrayList<>();
        for (Scored s : scored) {
            if (!s.decrypted() || s.dictLevel() >= 3) continue;
            String text = s.text().strip();
            if (text.isEmpty() || text.length() > 120) continue;
            boolean hit = false;
            for (String k : keywords) if (text.contains(k)) { hit = true; break; }
            if (!hit) continue;
            if (!seen.add(text)) continue;      // 按文本去重（同一句在库里重复很多次）
            picked.add(s);
            if (picked.size() >= limit) break;
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            for (Scored s : picked) {
                w.println("{\"text\":" + esc(s.text()) + ",\"dict_level\":" + s.dictLevel()
                        + ",\"dict_rule\":" + esc(s.dictRule()) + "}");
            }
        }
        System.out.println("挑出 " + picked.size() + " 条（关键词=" + String.join("/", keywords) + "）→ " + out.toAbsolutePath());
    }

    // ==================== ③ 对明文语料跑产线判定 ====================

    private static void judgePlain(String[] args) throws Exception {
        Path in = Path.of(args[1]);
        Path out = Path.of(args[2]);
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 4;

        GuardrailRuleService dict = realDictionary();
        List<Object[]> targets = new ArrayList<>();
        int n = 0;
        for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            String text = node.path("text").asText();
            String group = node.path("group").asText("plain");
            String label = node.path("label").asText("");
            if (text.isBlank()) continue;
            int level = -1;
            String rule = null;
            try {
                var v = dict.check(text);
                level = v.level();
                rule = v.ruleId();
            } catch (RuntimeException e) {
                level = -2;
            }
            Row row = new Row(n++, group, label, "", "");
            targets.add(new Object[]{new Scored(row, text, true, level, rule), group});
        }
        System.out.println("读入 " + targets.size() + " 条明文");
        List<Result> results = runJev(targets, threads);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            for (Result r : results) {
                w.println("{\"group\":" + esc(r.selectedBy())
                        + ",\"label\":" + esc(r.s().row().user())
                        + ",\"dict_level\":" + r.s().dictLevel()
                        + ",\"dict_rule\":" + esc(r.s().dictRule())
                        + ",\"probability\":" + (r.probability() == null ? "null" : r.probability())
                        + ",\"exceeds\":" + (r.exceeds() == null ? "null" : r.exceeds())
                        + ",\"ms\":" + r.ms()
                        + ",\"error\":" + esc(r.error())
                        + ",\"text\":" + esc(r.s().text()) + "}");
            }
        }
        System.out.println("写出 " + out.toAbsolutePath());
    }

    // ==================== 共用 ====================

    /** 全量解密 + 词典判定（本地，零外部调用）。 */
    private static List<Scored> scoreAll(List<Row> all) throws Exception {
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
        return scored;
    }

    /** 对一批 (Scored, 分组标签) 调真实第二信号并并发跑批。 */
    private static List<Result> runJev(List<Object[]> targets, int threads) throws Exception {
        String key = System.getenv("JEV_KEY");
        if (key == null || key.isBlank()) throw new IllegalStateException("JEV_KEY 未设置");
        JevProperties props = new JevProperties();
        props.setEnabled(true);
        props.setApiKey(key);
        props.setTimeoutMs(8000);
        props.getGuardrail().setMode(JevProperties.Mode.SHADOW);
        props.getGuardrail().setMinProbability(0.6);
        JevSelfHarmSignal signal = new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
        System.out.println("[4] Jev: mode=" + signal.mode() + " threshold=" + signal.threshold()
                + " available=" + signal.enabled());

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
                long t0 = System.nanoTime();
                try {
                    var risk = signal.judge(s.text());
                    ms = (System.nanoTime() - t0) / 1_000_000;
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
                    error = e.getClass().getSimpleName();
                    jevFail.incrementAndGet();
                    failures.computeIfAbsent(error, k -> new AtomicInteger()).incrementAndGet();
                }
                return new Result(s, selectedBy, probability, exceeds, ms, error);
            }));
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.MINUTES);
        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) results.add(f.get());
        results.sort(Comparator.comparingLong(r -> r.s().row().id()));
        System.out.println("[5] Jev 调用 " + targets.size() + " 次，失败 = " + jevFail.get() + " " + failures);
        return results;
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
