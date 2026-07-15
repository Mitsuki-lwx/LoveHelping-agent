package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.agent.LoveManus;
import cn.lwx.lwxaiagent.app.LoveApp;
import io.reactivex.Emitter;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class AiController {
    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] toolCallbacks;

    @Resource
    private ChatModel deepseekChatModel;

    private final ConcurrentHashMap<String, LoveManus> activeSessions = new ConcurrentHashMap<>();

    @GetMapping("Love_app/chat/sync")
    public String chatSync(String prompt,String chatId) {
        return loveApp.doChat(prompt,chatId);
    }
    // 流式响应端点：使用 Server-Sent Events (SSE) 实现实时聊天。PRODUCES 指定响应类型为 text/event-stream，适合前端实时接收数据。
    @GetMapping(value = "Love_app/chat/sse",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSse(String prompt, String chatId) {
        return loveApp.doChatByStream(prompt,chatId);
    }

    @GetMapping(value = "Love_app/chat/sse")
    public Flux<ServerSentEvent<String>> chatSseServer(String prompt, String chatId) {
        //这是一个流式响应端点，返回 Server-Sent Events (SSE) 格式的数据流。前端可以通过 EventSource 接收实时数据。
        return loveApp.doChatByStream(prompt,chatId)
                .map(trunk-> ServerSentEvent.<String>builder(trunk).data(trunk).build());//将每个数据块封装为 ServerSentEvent 对象，便于前端解析。
                // 注意：这里的 map 操作将 Flux<String> 转换为 Flux<ServerSentEvent<String>>，确保前端能够正确处理 SSE 数据流。
                //.data是 ServerSentEvent 的一个方法，用于设置事件的数据部分。前端接收到的事件数据就是这个部分的内容。
                //整个链式调用的过程是先.builder()创建一个 ServerSentEvent.Builder 对象，然后通过.data(trunk)设置事件的数据，
                // 最后通过.build()生成最终的 ServerSentEvent 对象。
    }

    @GetMapping(value = "Love_app/chat/sse_emitter")
    public SseEmitter chatSseEmitter(String prompt, String chatId) {
        //先创建一个emitter
        SseEmitter emitter = new SseEmitter();
        loveApp.doChatByStream(prompt,chatId)
                .subscribe(
                        trunk -> {
                            try {
                                emitter.send(trunk);
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> emitter.completeWithError(error),
                        () -> emitter.complete()
                );
        //流程是：1.创建一个 SseEmitter 对象；2.调用 loveApp.doChatByStream 获取数据流；
        // 3.订阅数据流，接收到每个数据块时通过 emitter.send 发送给前端；4.处理错误和完成事件。
        //subscribe 方法的三个参数分别是：onNext、onError 和 onComplete 回调函数，分别处理数据流中的每个元素、错误事件和完成事件。
        return emitter;
    }

    @GetMapping(value = "Love_app/chat/LoveManus")
    public SseEmitter doChatWithLoveManus(String message, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        String finalSessionId = sessionId;
        LoveManus loveManus = new LoveManus(toolCallbacks, deepseekChatModel);
        activeSessions.put(finalSessionId, loveManus);

        SseEmitter emitter = loveManus.runStream(message);

        // 清理会话
        emitter.onCompletion(() -> activeSessions.remove(finalSessionId));
        emitter.onTimeout(() -> activeSessions.remove(finalSessionId));
        emitter.onError(e -> activeSessions.remove(finalSessionId));

        // 以 header 形式返回 sessionId，方便前端后续发停止请求
        // 前端在 EventSource 的 onopen 中可以读取此响应头
        return emitter;
    }

    @GetMapping("Love_app/chat/LoveManus/stop/{sessionId}")
    public String stopLoveManus(@PathVariable String sessionId) {
        LoveManus agent = activeSessions.get(sessionId);
        if (agent != null) {
            agent.stop();
            activeSessions.remove(sessionId);
            return "stopped";
        }
        return "no_active_session";
    }
}
