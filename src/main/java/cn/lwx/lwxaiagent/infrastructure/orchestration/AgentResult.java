package cn.lwx.lwxaiagent.infrastructure.orchestration;

import reactor.core.publisher.Flux;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一执行结果：屏蔽浅层（Flux）和深层（SseEmitter）的差异。
 *
 * <p>ChatEntry 根据 RoutingResult.depth() 返回不同子类型，
 * AiController 统一处理——浅层走 Flux→SSE 桥接，深层直接返回 SseEmitter。</p>
 */
public sealed interface AgentResult {

    /** 增强浅层：ChatClient 单次 LLM 调用，Flux 流式输出 */
    record ShallowResult(Flux<String> flux) implements AgentResult {}

    /** 增强深层：ReactAgent 多步循环，SseEmitter 推送 */
    record DeepResult(SseEmitter emitter) implements AgentResult {}
}
