package cn.lwx.lwxaiagent.config;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * ADR-48：bigmodel 降为**最低一级**兜底。
 *
 * <p>降级链（见 {@code LlmGateway}）：<br>
 * primary = OpenRouter {@code qwen/qwen-plus}（实测 42.9~51.1 tok/s，prompt cache 命中 88%）<br>
 * → fallback = DashScope {@code qwen-plus}<br>
 * → lastResort = bigmodel {@code glm-4-flash}（本类，<b>最低等级</b>）</p>
 *
 * <p><b>为什么保留而不删</b>：bigmodel 是本仓唯一一个"有 key、已跑通、已做过容量实测"的端点
 * （11~14 tok/s、并发上限 ≈24）。真断网或前两级全挂时，它仍是一个已知可用的兜底；
 * 删掉会让"全挂"从"降级文案"直接变成硬 5000。且它的速率虽慢，但 300 token 的短回答
 * （约 21~27s）仍落在 {@code attempt-timeout-ms=45000} 之内。</p>
 *
 * <p><b>凭据</b>：只从环境变量 {@code BIGMODEL_API_KEY} / {@code BIGMODEL_BASE_URL} 读。
 * 缺 key 时**不抛异常、不阻断启动**（沿用 embedding/rerank 的既有约定：凭据在<b>调用期</b>校验，
 * 缺了就在真实调用时报错并走熔断降级）——否则一个最低等级的兜底端点会把整个应用拖不起来。</p>
 *
 * <p><b>回滚</b>：把 {@code app.llm.last-resort-enabled} 设为 {@code false} 即退回两级链，
 * 无需改代码。</p>
 */
@Configuration
public class BigModelLastResortConfig {
    private static final Logger log = LoggerFactory.getLogger(BigModelLastResortConfig.class);

    /**
     * @param enabled 开关；置 false 可退回两级降级链（回滚用）
     * @param baseUrl bigmodel OpenAI 兼容端点，形如 {@code https://open.bigmodel.cn/api/paas/v4}
     * @param apiKey  凭据，<b>空则不注册 bean</b>（而不是注册一个必然失败的）
     * @param model   模型名
     */
    @Bean("bigModelChatModel")
    public ChatModel bigModelLastResortModel(
            @Value("${app.llm.last-resort-enabled:true}") boolean enabled,
            @Value("${BIGMODEL_BASE_URL:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${BIGMODEL_API_KEY:}") String apiKey,
            @Value("${BIGMODEL_MODEL:glm-4-flash}") String model,
            ObservationRegistry observationRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ToolCallingManager containerToolCallingManager) {
        if (!enabled) {
            log.info("[ADR-48] bigmodel 兜底已按 app.llm.last-resort-enabled=false 关闭，降级链退回两级");
            return null;
        }
        if (apiKey == null || apiKey.isBlank()) {
            // 不抛异常：最低等级的兜底端点缺凭据，不该让整个应用起不来。
            // LlmGateway 对 null 的 lastResort 会自动跳过这一级，退回两级链。
            log.warn("[ADR-48] BIGMODEL_API_KEY 未注入 —— bigmodel 兜底级不生效，"
                    + "降级链退回两级（OpenRouter → DashScope）。这是配置缺失，不是故障。");
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
        return new OpenAiChatModel(api,
                org.springframework.ai.openai.OpenAiChatOptions.builder().model(model).build(),
                toolCallingManager, RetryTemplate.builder().maxAttempts(1).build(), observationRegistry);
    }
}
