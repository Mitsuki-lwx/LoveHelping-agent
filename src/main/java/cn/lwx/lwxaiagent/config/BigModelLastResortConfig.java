package cn.lwx.lwxaiagent.config;

import cn.lwx.lwxaiagent.infrastructure.ai.LlmFallbackTier;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * ADR-48：bigmodel 作为降级链**最低一级**兜底（本类负责注册该 bean）。
 *
 * <p><b>ADR-51（2026-09-27）起默认关闭</b>（{@code app.llm.last-resort-enabled=false}），
 * 因为实测该级返回 <b>400 Bad Request</b>（根因未查清，缺 {@code BIGMODEL_API_KEY} 无法复现）。
 * <b>不删类、不删注册逻辑</b>——根因查清或换模型后，把开关拨回 {@code true} 即可复活。</p>
 *
 * <p><b>历史链形</b>（改代码时别照旧注释理解，ADR-51 前的形态）：
 * primary = OpenRouter {@code stealth/space-bunny-alpha}（实测 42.9~51.1 tok/s、prompt cache 命中 88%）
 * → fallback = DashScope {@code qwen-plus} → lastResort = bigmodel {@code glm-4-flash}（本类）。
 * ADR-51 后 primary 已是 DeepSeek 官方 {@code deepseek-flash}。</p>
 *
 * <p><b>为什么保留而不删</b>：bigmodel 是本仓唯一一个"有 key、已跑通、已做过容量实测"的端点
 * （11~14 tok/s、并发上限 ≈24）。真断网或主链全挂时，它仍是一个已知可用的兜底；
 * 删掉会让"全挂"从"降级文案"直接变成硬 5000。且它的速率虽慢，但 300 token 的短回答
 * （约 21~27s）仍落在 {@code attempt-timeout-ms=45000} 之内。</p>
 *
 * <p><b>凭据</b>：只从环境变量 {@code BIGMODEL_API_KEY} / {@code BIGMODEL_BASE_URL} 读。
 * 缺 key 时**不抛异常、不阻断启动**（沿用 embedding/rerank 的既有约定：凭据在<b>调用期</b>校验，
 * 缺了就在真实调用时报错并走熔断降级）——否则一个最低等级的兜底端点会把整个应用拖不起来。</p>
 *
 * <p><b>回滚</b>：把 {@code app.llm.last-resort-enabled} 设为 {@code true} 即恢复本兜底级，
 * 无需改代码。</p>
 *
 * <p><b>ADR-52 的变化</b>：bean 类型从 {@code ChatModel} 变为 {@link LlmFallbackTier}，
 * 并加 {@code @Order(20)}（排在 DashScope 的 {@code @Order(10)} 之后）。
 * 关掉本级的开关现在是 {@code @ConditionalOnProperty}（构建期不注册），
 * <b>不再与"降级是否发生"混用同一个开关</b> —— 后者是
 * {@code app.llm.degrade-enabled}（运行时总闸，见 {@code LlmGatewayProperties#degradeEnabled}）。
 * 这两个开关由此彻底解耦：想"只开 bigmodel、不开 DashScope"是<b>可以做到</b>的
 * （{@code degrade-enabled=true} + {@code fallback-enabled=false} + {@code last-resort-enabled=true}）。</p>
 *
 * <p><b>回滚</b>：把 {@code app.llm.last-resort-enabled} 设为 {@code true} 即恢复本兜底级，
 * 无需改代码。</p>
 */
@Configuration
public class BigModelLastResortConfig {
    private static final Logger log = LoggerFactory.getLogger(BigModelLastResortConfig.class);

    /**
     * @param enabled 开关；置 false 则本兜底 bean 不注册（ADR-51 起默认 false）
     * @param baseUrl bigmodel OpenAI 兼容端点，形如 {@code https://open.bigmodel.cn/api/paas/v4}
     * @param apiKey  凭据，<b>空则返回 null（不注册）</b>，而不是注册一个必然失败的
     * @param model   模型名
     * @return 降级链最后一级；{@code null} 表示本级不存在（网关会跳过）
     */
    @Bean("bigModelLastResortTier")
    @org.springframework.core.annotation.Order(20)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.llm.last-resort-enabled", havingValue = "true")
    public LlmFallbackTier bigModelLastResortTier(
            @Value("${BIGMODEL_BASE_URL:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${BIGMODEL_API_KEY:}") String apiKey,
            @Value("${BIGMODEL_MODEL:glm-4-flash}") String model,
            ObservationRegistry observationRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ToolCallingManager containerToolCallingManager) {
        if (apiKey == null || apiKey.isBlank()) {
            // 不抛异常：最低等级的兜底端点缺凭据，不该让整个应用起不来。
            // 返回 null → Spring 记为 NullBean → 网关的 ObjectProvider 流里可能解出 null，
            // 由 LlmGateway 的 filter(Objects::nonNull) 兜掉。
            log.warn("[ADR-48/52] BIGMODEL_API_KEY 未注入 —— bigmodel 兜底级不生效，本 bean 不注册。"
                    + "这是配置缺失，不是故障。");
            return null;
        }
        log.info("[ADR-48] bigmodel 兜底已注册：base={} model={}（降级链最低等级）", baseUrl, model);
        // Spring AI 默认 completions-path 是 /v1/chat/completions，
        // 而 bigmodel 是 /api/paas/v4/chat/completions —— 不显式指定会 404（历史上踩过）。
        var api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        // ⚠️ toolCallingManager 不能为 null（OpenAiChatModel 构造器直接断言）——
        //    传 null 会让整个应用启动失败，而"最低等级的兜底端点"不该有这种权力。
        //    优先用容器里 Spring AI 自动配好的那一个（与主模型共用同一套工具解析）；
        //    万一某环境没有这个 bean，自建一个而不是让启动失败。
        var toolCallingManager = containerToolCallingManager != null
                ? containerToolCallingManager
                : org.springframework.ai.model.tool.DefaultToolCallingManager.builder()
                        .observationRegistry(observationRegistry).build();
        // retry maxAttempts=1：重试的唯一所有者是 LlmGateway（ADR-23），
        // 底层 client 再自带重试会绕过闸门与熔断，是"两个重试所有者"。
        var chatModel = new OpenAiChatModel(api,
                org.springframework.ai.openai.OpenAiChatOptions.builder().model(model).build(),
                toolCallingManager, RetryTemplate.builder().maxAttempts(1).build(), observationRegistry);
        // baseUrl 一并带进 tier，供 LlmGateway 打"生效端点"指标（ADR-52 修口子 3：
        // 网关不再按 "last-resort" 字面量分支去猜端点）。
        return new LlmFallbackTier("last-resort", chatModel, baseUrl);
    }
}
