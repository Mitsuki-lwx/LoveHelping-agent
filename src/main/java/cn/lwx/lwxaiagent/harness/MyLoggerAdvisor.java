package cn.lwx.lwxaiagent.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * <h1>自定义日志拦截器 —— 记录 AI 请求与响应的完整日志</h1>
 *
 * <p><strong>核心作用：</strong>作为 Spring AI ChatClient 的 Advisor（拦截器），在每次 AI 调用
 * 的前后记录请求内容和响应内容，提供完整的审计追踪和调试能力。</p>
 *
 * <h2>Spring AI Advisor 机制</h2>
 * <p>在 Spring AI 框架中，Advisor 是一个拦截器链机制，类似 Servlet Filter 或 Spring Interceptor。
 * 每个 Advisor 可以在 AI 调用前后执行自定义逻辑。本类同时实现了两个接口：</p>
 * <ul>
 *   <li><b>{@link CallAdvisor}：</b>处理同步调用（非流式），一次性获取完整响应</li>
 *   <li><b>{@link StreamAdvisor}：</b>处理流式调用（SSE），逐 Token 返回响应</li>
 * </ul>
 *
 * <h2>与 SimpleLoggerAdvisor 的区别</h2>
 * <p>Spring AI 内置的 {@link SimpleLoggerAdvisor} 也提供日志功能，但本类的优势在于：</p>
 * <ul>
 *   <li><b>中文友好：</b>日志格式更适合中文场景的阅读习惯</li>
 *   <li><b>可定制：</b>可以按需修改日志格式、日志级别、过滤敏感信息等</li>
 *   <li><b>完整性：</b>同时覆盖同步和流式两种调用模式</li>
 * </ul>
 *
 * <h2>拦截器执行顺序</h2>
 * <p>{@code getOrder()} 返回 <b>0</b>，表示在所有 Advisor 中<b>最先执行</b>。
 * 这样可以在其他 Advisor（如安全护栏、RAG 知识检索）处理之前和之后都能看到原始请求和最终响应。</p>
 */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * <h3>获取 Advisor 名称</h3>
     *
     * <p>返回当前类的简单名称 "MyLoggerAdvisor"，用于在日志和 Advisor 链中标识此拦截器。</p>
     *
     * @return Advisor 名称字符串
     */
    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * <h3>获取执行顺序</h3>
     *
     * <p>返回 <b>0</b>，表示在所有 Advisor 中最先执行。</p>
     * <p><strong>设计意图：</strong>日志拦截器应该处于最外层，这样才能记录到"原始请求"和"最终响应"，
     * 而非被其他 Advisor 修改后的中间状态。</p>
     *
     * @return 执行顺序值，0 表示最高优先级（最先执行）
     */
    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * <h3>拦截同步调用（非流式）</h3>
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li><b>请求前：</b>调用 {@link #logRequest} 记录 AI 请求的完整提示词</li>
     *   <li><b>调用链：</b>调用 {@code chain.nextCall(request)} 将请求传递给下一个 Advisor，
     *       最终到达 AI 模型</li>
     *   <li><b>响应后：</b>调用 {@link #logResponse} 记录 AI 返回的完整响应文本</li>
     *   <li><b>返回：</b>将原始响应原封不动地返回给调用者</li>
     * </ol>
     *
     * @param chatClientRequest  AI 请求对象，包含提示词、模型参数等
     * @param callAdvisorChain   Advisor 调用链，用于将请求传递给下一个拦截器
     * @return AI 响应对象，包含模型生成的文本结果和元数据
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        logRequest(chatClientRequest);
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);
        logResponse(chatClientResponse);
        return chatClientResponse;
    }

    /**
     * <h3>拦截流式调用（SSE 流式返回）</h3>
     *
     * <p>流式调用的处理比同步调用更复杂，因为响应是分多个 Token 逐步返回的。
     * 需要借助 {@link ChatClientMessageAggregator} 将所有 Token 聚合后再进行日志记录。</p>
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li><b>请求前：</b>调用 {@link #logRequest} 记录请求日志（与同步调用一致）</li>
     *   <li><b>流式调用：</b>调用 {@code chain.nextStream(request)} 获取流式响应</li>
     *   <li><b>聚合响应：</b>使用 {@link ChatClientMessageAggregator#aggregateChatClientResponse}
     *       将流式 Flux 聚合为完整的响应，在聚合完成时回调 {@link #logResponse} 记录完整响应</li>
     * </ol>
     *
     * @param chatClientRequest   AI 请求对象
     * @param streamAdvisorChain  Advisor 流式调用链
     * @return 流式 AI 响应 Flux，以 Server-Sent Events 形式逐步返回
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        logRequest(chatClientRequest);
        Flux<ChatClientResponse> chatClientResponses = streamAdvisorChain.nextStream(chatClientRequest);
        return new ChatClientMessageAggregator().aggregateChatClientResponse(chatClientResponses, this::logResponse);
    }

    /**
     * <h3>记录 AI 请求日志</h3>
     *
     * <p>以 INFO 级别输出完整的提示词内容。提示词包括系统提示词（system prompt）、
     * 历史消息和用户当前消息的拼接结果。</p>
     *
     * @param request AI 请求对象
     */
    private void logRequest(ChatClientRequest request) {
        log.info("AI Request: {}", request.prompt());
    }

    /**
     * <h3>记录 AI 响应日志</h3>
     *
     * <p>以 INFO 级别输出模型返回的文本内容。从响应的嵌套结构中提取最终的输出文本：
     * {@code chatResponse → Result → Output → Text}。</p>
     *
     * @param chatClientResponse AI 响应对象
     */
    private void logResponse(ChatClientResponse chatClientResponse) {
        log.info("response: {}", chatClientResponse.chatResponse().getResult().getOutput().getText());
    }
}
