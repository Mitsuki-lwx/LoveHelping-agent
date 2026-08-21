package cn.lwx.lwxaiagent.infrastructure.ai;

import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 执行指标拦截器 —— 记录 LLM 和工具调用的耗时/次数/结果。
 * 注册到 ReactAgent.builder().interceptors()。
 */
@Slf4j
@Component
public class AgentMetricsInterceptor extends ModelInterceptor {

    private final MeterRegistry meterRegistry;

    /** LLM 调用计时器 */
    private final Timer llmTimer;
    /** 工具调用计时器 */
    private final Timer toolTimer;

    public AgentMetricsInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.llmTimer = Timer.builder("agent.llm.duration")
                .description("Agent LLM call duration")
                .register(meterRegistry);
        this.toolTimer = Timer.builder("agent.tool.duration")
                .description("Agent tool call duration")
                .register(meterRegistry);
    }

    @Override
    public String getName() {
        return "agent-metrics-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long start = System.currentTimeMillis();
        try {
            ModelResponse response = handler.call(request);
            long took = System.currentTimeMillis() - start;
            sample.stop(llmTimer);
            meterRegistry.counter("agent.llm.calls", "status", "success").increment();
            log.debug("Agent LLM call: {}ms, tools={}", took,
                    request.getTools() != null ? request.getTools().size() : 0);
            return response;
        } catch (Exception e) {
            sample.stop(llmTimer);
            meterRegistry.counter("agent.llm.calls", "status", "error").increment();
            log.warn("Agent LLM call failed: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public java.util.List<org.springframework.ai.tool.ToolCallback> getTools() {
        return java.util.List.of();
    }
}