package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;

/** The transport owns its subscription. Disconnect/timeout must cancel model and graph work. */
final class SseBridge {
    private SseBridge() {}

    /**
     * 首字节前的占位事件名。
     *
     * <p><b>为什么必须是独立 event 而不是默认 data</b>：默认 data 事件会走前端的
     * {@code onmessage}，被当成正文插进对话气泡 → 用户看到「脏文本」。
     * 用独立 event 名则**未监听的 event 会被浏览器直接丢弃**，
     * 意味着前端没适配也不会坏 —— 这是向后兼容的关键。</p>
     *
     * <p><b>为什么需要它</b>：phase16 实测主端点存在首字节超时
     * （{@code attempt=3 kind=FIRST_BYTE firstByteTimeoutMs=45000}），
     * 而本方法在订阅后<b>直接等上游第一个 token</b> →
     * 上游不吐就一个字节都不发，用户看到的是<b>白屏 45 秒</b>。
     * 「等 N 秒」和「不知道系统在干什么」是两种完全不同的体感。</p>
     */
    static final String STATUS_EVENT = "status";

    /**
     * 占位文案。
     *
     * <p>⛔ 必须<b>诚实</b>：此刻可能还在建连接/检索，<b>并没有</b>「在分析你的问题」。
     * 写「正在分析…」会暗示系统正在做实际工作，那是拿不存在的进展安抚用户。</p>
     */
    static final String THINKING_STATUS = "{\"stage\":\"thinking\",\"text\":\"让我想想…\"}";

    static SseEmitter emitter(Flux<String> flux) {
        return emitter(flux, THINKING_STATUS);
    }

    /**
     * @param thinkingStatus 建连后立即发出的占位载荷；传 {@code null} 则不发（保留给不需要占位的场景）
     */
    static SseEmitter emitter(Flux<String> flux, String thinkingStatus) {
        SseEmitter emitter = new SseEmitter(95_000L);
        var subscription = Disposables.swap();
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> { subscription.dispose(); emitter.complete(); });
        emitter.onError(error -> subscription.dispose());
        // ⭐ 占位事件必须在**订阅之前**发：订阅后就是等上游第一个 token 了，
        //    而那正是我们要消除的白屏。发失败不致命 —— 客户端可能已经断开。
        if (thinkingStatus != null && !thinkingStatus.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name(STATUS_EVENT).data(thinkingStatus));
            } catch (Exception ignored) {
                // 占位发不出去就说明连接已断，交给下面的订阅去暴露真正的错误
            }
        }
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

    /**
     * ⭐ **占位优先**的入口：先建 emitter、立刻发占位，再在**独立线程**上跑业务。
     *
     * <p><b>为什么必须有这个方法</b>（2026-09-27 实测）：本类原有的
     * {@code emitter(Flux)} 要求调用方<b>先有 flux</b>，而 flux 来自
     * {@code chatEntry.chat()} —— 那是<b>同步阻塞</b>的（实测 1~16s，含建图/路由/RAG 准备）。
     * 于是顺序被锁死成「跑完业务 → 建 emitter → 发占位」，
     * 占位事件<b>永远晚于</b>它本该缓解的那段等待。</p>
     *
     * <p>实测对照（{@code probe_sse_path_ab.py}，真实链路 3 轮）：
     * 两条 SSE 路径首帧都要 <b>16~50s</b>，远达不到「立即」。
     * ⚠️ 结论：<b>瓶颈不在返回类型，在业务前置耗时</b> ——
     * RAG 检索+rerank 本身只要 1.4s（{@code Rerank ok in 549~1104ms}），
     * 大头在 {@code chat()} 里的其他同步工作。<b>改 SSE 层治不了这个</b>。</p>
     *
     * <p>本方法保证的是：<b>占位帧在业务开始前就写出</b>，剩下的耗时落在流上。
     * 它是必要条件，不是充分条件。</p>
     *
     * @param business 产出 flux 的业务动作，会在<b>异步线程</b>上执行
     */
    static SseEmitter emitterWithPlaceholder(java.util.function.Supplier<Flux<String>> business) {
        return emitterWithPlaceholder(THINKING_STATUS, business);
    }

    static SseEmitter emitterWithPlaceholder(String thinkingStatus,
                                             java.util.function.Supplier<Flux<String>> business) {
        SseEmitter emitter = new SseEmitter(95_000L);
        var subscription = Disposables.swap();
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> { subscription.dispose(); emitter.complete(); });
        emitter.onError(error -> subscription.dispose());
        // ① 立刻发占位：此时还没有任何业务在跑，这是本方法存在的全部意义
        if (thinkingStatus != null && !thinkingStatus.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name(STATUS_EVENT).data(thinkingStatus));
            } catch (Exception ignored) {
                // 客户端可能已断开；真正的错误由下面订阅时暴露
            }
        }
        // ② 业务丢到异步线程：chatEntry.chat() 同步阻塞 1~16s，绝不能占着请求线程
        Thread worker = new Thread(() -> {
            Flux<String> flux;
            try {
                flux = business.get();
            } catch (Throwable t) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(safeMessage(t)));
                    emitter.complete();
                } catch (Exception ignored) { emitter.completeWithError(t); }
                return;
            }
            if (flux == null) { emitter.complete(); return; }
            // ③ 客户端可能在业务跑完之前就断了 —— 那时不能再往里写
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
        }, "sse-business");
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    static String safeMessage(Throwable error) {
        return error instanceof BizException b ? b.getMessage() : "AI 服务暂时不可用，请稍后再试";
    }
}
