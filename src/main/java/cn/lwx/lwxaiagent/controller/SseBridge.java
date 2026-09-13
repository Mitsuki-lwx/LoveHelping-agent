package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;

/** The transport owns its subscription. Disconnect/timeout must cancel model and graph work. */
final class SseBridge {
    private SseBridge() {}

    static SseEmitter emitter(Flux<String> flux) {
        SseEmitter emitter = new SseEmitter(95_000L);
        var subscription = Disposables.swap();
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> { subscription.dispose(); emitter.complete(); });
        emitter.onError(error -> subscription.dispose());
        subscription.update(flux.subscribe(text -> {
            try {
                if (text.startsWith(ChatExecutor.ADVICE_EVENT_MARKER))
                    emitter.send(SseEmitter.event().name("advice").data(text.substring(ChatExecutor.ADVICE_EVENT_MARKER.length())));
                else emitter.send(text);
            } catch (Exception error) { subscription.dispose(); emitter.completeWithError(error); }
        }, error -> {
            try {
                emitter.send(SseEmitter.event().name("error").data(safeMessage(error)));
                emitter.complete();
            } catch (Exception sendError) { emitter.completeWithError(sendError); }
        }, emitter::complete));
        return emitter;
    }

    static String safeMessage(Throwable error) {
        return error instanceof BizException b ? b.getMessage() : "AI 服务暂时不可用，请稍后再试";
    }
}
