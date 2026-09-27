package cn.lwx.lwxaiagent.infrastructure.orchestration;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * <b>prompt 载荷取证（默认关闭，纯诊断工具）。</b>
 *
 * <p><b>为什么需要它</b>：2026-09-27 实测发现真实业务链路的 prompt cache 命中
 * 落在<b>两个离散值</b>（141 / 1180）上，而不是连续分布。已排除的解释（每个都做过对照实验）：
 * <ul>
 *   <li>❌「advisor 注入顺序断了前缀」—— 对照实验证明尾部追加动态段命中仍 98.3%、
 *       空/有交替 99.8%，尾部注入<b>无害</b>（{@code probe_cache_mechanism.py} I/J/K 组）</li>
 *   <li>❌「后端副本差异」—— 同一 prompt 连发 12 次命中<b>恒定</b>在 138，一次都没跳
 *       （{@code probe_cache_stability.py}）</li>
 *   <li>❌「prompt 长度门槛」—— mechanism 组 1329 token 命中 98%+，
 *       而真实链路 141 那轮 prompt=2323（更长）却只有 6.1%</li>
 * </ul>
 * 剩下的唯一可能只能在<b>真实装配结果</b>里找，而此前<b>没有任何地方记录过实际发出的
 * messages 载荷</b> —— 这与 ADR-44（按名字丢 span）、ADR-46（载荷契约错位）、
 * ADR-48（生效端点零痕迹）<b>是同一类盲区</b>：出问题时要靠推断，而推断已经错了三次。
 *
 * <p><b>开关</b>：环境变量 {@code PROMPT_DUMP_DIR}。<b>不设就完全不执行</b>（一次字符串判空），
 * 生产环境无开销、不会误写。
 *
 * <p><b>落盘内容</b>：每次调用一个 JSONL 行，含 system 各段长度 + <b>各自 SHA-256 前 16 位</b>
 * + 段首 120 字。<b>不落用户原文</b>（用户输入/记忆内容可能含隐私），长度+哈希已足够定位
 * 「哪一段变了」——这是 ADR-13「打分型量具必须留存被评原文」与隐私之间的折中：
 * 保留可定位的结构指纹，不保留可读的敏感内容。
 */
@Slf4j
final class PromptPayloadDump {

    private PromptPayloadDump() {
    }

    static void dump(String chatId, boolean advice, boolean rag,
                     String effectivePrompt, String context) {
        String dir = System.getenv("PROMPT_DUMP_DIR");
        if (dir == null || dir.isBlank()) return;  // 默认关闭：绝大多数调用直接返回
        try {
            Path p = Path.of(dir);
            Files.createDirectories(p);
            String line = String.format(
                    "{\"chatId\":\"%s\",\"advice\":%s,\"rag\":%s,"
                            + "\"systemChars\":%d,\"systemHash\":\"%s\",\"systemHead\":\"%s\","
                            + "\"contextChars\":%d,\"contextHash\":\"%s\",\"contextHead\":\"%s\","
                            + "\"totalChars\":%d}%n",
                    chatId, advice, rag,
                    effectivePrompt.length(), sha16(effectivePrompt), head(effectivePrompt),
                    context.length(), sha16(context), head(context),
                    effectivePrompt.length() + context.length());
            Files.writeString(p.resolve("prompt-payload.jsonl"), line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 诊断工具绝不能影响主链路
            log.warn("PromptPayloadDump 写入失败（已忽略）: {}", e.getMessage());
        }
    }

    private static String sha16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            return "ERR";
        }
    }

    private static String head(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120);
    }
}
