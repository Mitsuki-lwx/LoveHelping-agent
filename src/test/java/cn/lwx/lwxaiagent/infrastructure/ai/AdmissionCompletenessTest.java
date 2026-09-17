package cn.lwx.lwxaiagent.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-23 / ADR-31（发现三）的架构守护：<b>单一准入点</b>。
 *
 * <p>{@code ChatModelConfig} 把 {@code LlmGateway} 声明为 {@code @Primary}，因此"注入裸
 * {@code ChatModel}"的消费者天然经过网关，也就天然享有并发许可、供应商熔断、重试预算与
 * {@code llm.usage.owner=gateway} 用量归因。能绕过这一切的只有一种写法：显式
 * {@code @Qualifier} 直连供应商模型。</p>
 *
 * <p>本测试扫描源码，而不是装配 Spring 上下文。理由：它表达的是<b>不变式本身</b>，
 * 对将来新增的消费者自动生效；而运行时用例只能覆盖已经写出来的路径。
 * ADR-31 发现三正是靠人眼审查才发现的——补上这条守护，下次不必再靠人眼。</p>
 */
class AdmissionCompletenessTest {

    /** 唯一合法例外：网关自身必须直连 primary 供应商模型——它正是被 {@code @Primary} 暴露出去的那个。 */
    private static final String GATEWAY = "cn/lwx/lwxaiagent/infrastructure/ai/LlmGateway.java";

    /**
     * 匹配任何形式的 {@code Qualifier("openAiChatModel")} / {@code Qualifier("deepSeekChatModel")}，
     * 含全限定写法 {@code @org.springframework.beans.factory.annotation.Qualifier(...)}。
     */
    private static final Pattern DIRECT_PROVIDER = Pattern.compile(
            "Qualifier\\(\\s*\"(openAiChatModel|deepSeekChatModel)\"\\s*\\)");

    @Test
    void onlyGatewayMayReachRawProviderModels() throws IOException {
        Path root = Paths.get("src", "main", "java");
        assumeTrue(Files.isDirectory(root), "跳过：测试未在模块根目录运行");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    // 注释/javadoc 里引用这种写法是为了说明「不要这么写」，不算违规。
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) continue;
                    if (DIRECT_PROVIDER.matcher(lines.get(i)).find()) {
                        offenders.add(root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1));
                    }
                }
            }
        }

        assertFalse(offenders.isEmpty(),
                "守护失效：一个直连点都没扫到，但网关自身应当被命中——请检查扫描逻辑或目录假设");
        List<String> violations = offenders.stream().filter(o -> !o.startsWith(GATEWAY)).toList();
        assertTrue(violations.isEmpty(),
                "以下位置绕过 LlmGateway 直连供应商模型，破坏 ADR-23 的单一准入点"
                        + "（并发许可 / 供应商熔断 / 重试预算 / 用量归因 全部失效）：" + violations);
    }
}
