package cn.lwx.lwxaiagent.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import reactor.core.publisher.Flux;

/** Metadata-only logging: never aggregate full completions just for logs (ADR-24). */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {
    @Override public String getName() { return getClass().getSimpleName(); }
    @Override public int getOrder() { return 0; }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        try { return chain.nextCall(request); }
        finally { log.debug("AI call finished durationMs={}", (System.nanoTime() - start) / 1_000_000); }
    }
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            long start = System.nanoTime();
            return chain.nextStream(request).doFinally(signal -> log.debug("AI stream outcome={} durationMs={}",
                    signal, (System.nanoTime() - start) / 1_000_000));
        });
    }
}
