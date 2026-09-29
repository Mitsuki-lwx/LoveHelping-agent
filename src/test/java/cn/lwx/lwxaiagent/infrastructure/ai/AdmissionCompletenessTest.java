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
     * 匹配任何形式的 {@code Qualifier("openAiChatModel")} 等直连供应商模型 bean 的写法，
     * 含全限定写法 {@code @org.springframework.beans.factory.annotation.Qualifier(...)}。
     *
     * <p>ADR-52：降级级的 bean 名从 {@code deepSeekChatModel} / {@code bigModelChatModel}
     * 改为 {@code dashScopeFallbackTier} / {@code bigModelLastResortTier}
     * （类型也从 {@code ChatModel} 变为 {@code LlmFallbackTier}）—— 名单同步更新。</p>
     */
    private static final Pattern DIRECT_PROVIDER = Pattern.compile(
            "Qualifier\\(\\s*\"(openAiChatModel|deepSeekChatModel|dashScopeFallbackTier|bigModelLastResortTier)\"\\s*\\)");

    /**
     * ADR-52 新增的绕过向量：{@code LlmFallbackTier} 里包着**裸供应商 {@code ChatModel}**，
     * 所以"注入一个 tier"等价于"绕过网关拿到裸模型"。合法的提及者只有四个文件。
     */
    private static final Pattern RAW_TIER_TYPE = Pattern.compile("\\bLlmFallbackTier\\b");

    /** 允许提及 {@link cn.lwx.lwxaiagent.infrastructure.ai.LlmFallbackTier} 的文件（装配者 + 链路类型 + 唯一消费者 + 类型自身）。 */
    private static final List<String> TIER_ALLOWED = List.of(
            "cn/lwx/lwxaiagent/infrastructure/ai/LlmFallbackTier.java",
            "cn/lwx/lwxaiagent/infrastructure/ai/LlmProviderChain.java",
            "cn/lwx/lwxaiagent/infrastructure/ai/LlmGateway.java",
            "cn/lwx/lwxaiagent/config/LlmProviderConfig.java");

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

        // 扫描器自检（ADR-58）：原先靠"网关必然命中"来证明正则没写坏，但网关已改为
        // 经 LlmProviderChain 注入、不再使用限定符，于是改用**合成样本**自检 ——
        // 仍然保证"正则一旦被改坏，这里先红"，不依赖真实命中。
        assertTrue(DIRECT_PROVIDER.matcher("new @Qualifier(\"openAiChatModel\") ChatModel x").find(),
                "守护失效：DIRECT_PROVIDER 正则已不再匹配它本该拦住的写法");
        List<String> violations = offenders.stream().filter(o -> !o.startsWith(GATEWAY)).toList();
        assertTrue(violations.isEmpty(),
                "以下位置绕过 LlmGateway 直连供应商模型，破坏 ADR-23 的单一准入点"
                        + "（并发许可 / 供应商熔断 / 重试预算 / 用量归因 全部失效）：" + violations);
    }

    /**
     * ADR-52 的补充守护：{@code LlmFallbackTier} 是**降级链的注入点**，
     * 它包着裸供应商 {@code ChatModel}，谁注入它谁就绕过了网关。
     * 只允许"类型自身 + 两个生产者 + 网关"提及它。
     */
    @Test
    void onlyTierProducersAndGatewayMayMentionFallbackTier() throws IOException {
        Path root = Paths.get("src", "main", "java");
        assumeTrue(Files.isDirectory(root), "跳过：测试未在模块根目录运行");

        List<String> mentions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) continue;
                    if (RAW_TIER_TYPE.matcher(line).find()) {
                        mentions.add(rel);
                        break;
                    }
                }
            }
        }

        assertFalse(mentions.isEmpty(),
                "守护失效：一个 LlmFallbackTier 提及点都没扫到，但类型自身应当被命中——请检查扫描逻辑");
        List<String> violations = mentions.stream().filter(m -> !TIER_ALLOWED.contains(m)).toList();
        assertTrue(violations.isEmpty(),
                "以下文件提及 LlmFallbackTier（它包着裸供应商 ChatModel）："
                        + "若在此注入它，等于绕过 LlmGateway 的闸门 / 熔断 / 重试 / 用量归因。"
                        + "确属合法请加入 TIER_ALLOWED 并说明理由：" + violations);
    }
}
