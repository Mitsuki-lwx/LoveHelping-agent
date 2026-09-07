package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 图执行线程池配置（2026-09-07，性能工程代码级准备）。
 * <p>此前 GraphRunner.runAsync 用 {@code CompletableFuture.supplyAsync} 无显式 executor → 走
 * ForkJoinPool.commonPool（并行度≈CPU 核数-1，不可配、全 JVM 共享）——与闸门 max-inflight=8
 * 同量级：调高闸门会先爆 commonPool。现独立池化，参数可调（docs/09 §6.1 性能工程）。
 */
@Configuration
public class GraphExecutorConfig {

    @Bean(name = "graphExecutor")
    public ThreadPoolTaskExecutor graphExecutor(
            @Value("${app.graph.executor.core-size:16}") int core,
            @Value("${app.graph.executor.max-size:64}") int max,
            @Value("${app.graph.executor.queue-capacity:200}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, core));
        executor.setMaxPoolSize(Math.max(core, max));
        executor.setQueueCapacity(Math.max(0, queue));
        executor.setThreadNamePrefix("graph-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
