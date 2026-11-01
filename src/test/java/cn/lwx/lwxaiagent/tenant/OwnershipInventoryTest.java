package cn.lwx.lwxaiagent.tenant;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h1>归属校验盘点（change security-hardening / task 2.3，方案 A）</h1>
 *
 * <p>枚举控制器里所有带 {@code {*Id}} 的端点，要求其处理函数在<b>方法体</b>内出现
 * 归属校验的某个信号。新增一个按 id 取数却没有任何校验的端点，这里会变红。</p>
 *
 * <p><b>它不是什么</b>：不是运行时断言，证明不了"跨用户一定 403"。行为正确性仍由
 * E2E 负向用例（跨用户读/删/抢注）与各服务的单测承担。它守的是<b>遗漏</b>，
 * 那正是历史上反复出现的那类缺陷。</p>
 *
 * <p><b>为什么不用登记表</b>：手写登记表会腐烂，而且"登记了但没实现"骗得过自己。
 * 这里直接读源码里的调用证据，没有可维护的第二份真相。</p>
 */
class OwnershipInventoryTest {

    /** 归属校验的可接受信号（任一出现即视为该端点"有防线"）。 */
    private static final List<String> SIGNALS = List.of(
            "checkOwnership(",   // MemoryController 的显式 helper
            "Ownership.",        // 统一收口（静态工具；ownership-rollout 后的主信号）
            "owned(",            // 既有 service 内的 owned(...) 语义
            "getUserId()",       // 控制器内显式比对当前用户
            "requireUserId()",   // 控制器 helper：把当前用户 id 传进按 user 过滤的查询
            "adminGuard",        // 管理端：AdminGuard 校验
            "@RequireRole"       // 方法级 RBAC
    );

    /** 端点声明：@XxxMapping 里出现 {xxxId} 形式的路径参数。 */
    private static final Pattern ID_ENDPOINT =
            Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\(([^)]*\\{[^}]*[Ii]d[^}]*\\}[^)]*)\\)");

    /** 方法体边界：粗粒度取到下一个 @XxxMapping 或类尾。 */
    private static final Pattern NEXT_METHOD = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping");

    @Test
    void every_id_endpoint_has_an_ownership_signal() throws IOException {
        Path controllerDir = Paths.get("src/main/java/cn/lwx/lwxaiagent/controller");
        assertTrue(Files.isDirectory(controllerDir), "找不到控制器目录：" + controllerDir.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        try (Stream<Path> files = Files.list(controllerDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = ID_ENDPOINT.matcher(src);
                while (m.find()) {
                    int start = m.end();
                    int end = src.length();
                    Matcher next = NEXT_METHOD.matcher(src);
                    if (next.find(start)) {
                        // 命中下一个映射注解时，回退到它所在方法的签名处，避免把下一个端点算进本方法体
                        end = src.lastIndexOf("\n", next.start()) >= 0 ? next.start() : src.length();
                    }
                    String body = src.substring(start, Math.min(end, src.length()));

                    String endpoint = file.getFileName() + " " + m.group(2).trim();
                    seen.add(endpoint);

                    boolean guarded = SIGNALS.stream().anyMatch(body::contains);
                    if (!guarded) {
                        int line = src.substring(0, m.start()).split("\n", -1).length;
                        violations.add(file.getFileName() + ":" + line + "  " + m.group(2).trim());
                    }
                }
            }
        }

        assertTrue(!seen.isEmpty(), "没有扫描到任何带 {*Id} 的端点，扫描器可能失效了");
        assertTrue(violations.isEmpty(),
                "以下端点按 id 取用户资源，但方法体内没有任何归属校验信号（" + SIGNALS + "）：\n  "
                        + String.join("\n  ", violations));
    }
}
