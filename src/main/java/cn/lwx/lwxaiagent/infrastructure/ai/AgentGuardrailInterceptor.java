package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import com.alibaba.cloud.ai.graph.agent.interceptor.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class AgentGuardrailInterceptor extends ToolInterceptor {

    private final GuardrailRuleService guardrailRuleService;
    private final MeterRegistry meterRegistry;

    public AgentGuardrailInterceptor(GuardrailRuleService guardrailRuleService,
                                     MeterRegistry meterRegistry) {
        this.guardrailRuleService = guardrailRuleService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String getName() {
        return "agent-guardrail-interceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        long start = System.currentTimeMillis();

        if (toolName == null || toolName.isBlank()) {
            meterRegistry.counter("agent.tool.calls", "tool", "unknown", "status", "blocked").increment();
            return ToolCallResponse.error("unknown", "tool name is empty", "INTERNAL_ERROR");
        }

        log.debug("Agent tool call: {}", toolName);

        try {
            ToolCallResponse response = handler.call(request);
            long took = System.currentTimeMillis() - start;

            meterRegistry.counter("agent.tool.calls", "tool", toolName, "status", "success").increment();
            Timer.builder("agent.tool.duration")
                    .tag("tool", toolName)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(took));

            log.debug("Agent tool result: {} ({}ms)", toolName, took);
            return response;

        } catch (Exception e) {
            meterRegistry.counter("agent.tool.calls", "tool", toolName, "status", "error").increment();
            log.warn("Agent tool {} failed: {}", toolName, e.getMessage());
            return ToolCallResponse.error(toolName, e.getMessage(), "TOOL_ERROR");
        }
    }
}