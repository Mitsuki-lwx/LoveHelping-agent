package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图 checkpoint 配置（ADR-19 CAP 长任务断点底座）：RedisSaver 接 CompileConfig。
 * <p>Redisson 独立客户端（不接管 Spring Data Redis），threadId 维度持久化图状态；
 * 任务级可靠性仍由 agent_task 心跳兜底（ADR-3）——checkpoint 为未来的断点恢复打底。</p>
 */
@Slf4j
@Configuration
public class GraphCheckpointConfig {

    @Bean
    public RedisSaver graphRedisSaver(@Value("${spring.data.redis.host:localhost}") String host,
                                      @Value("${spring.data.redis.port:6379}") int port,
                                      @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password == null || password.isBlank() ? null : password)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        RedissonClient redisson = Redisson.create(config);
        RedisSaver saver = RedisSaver.builder().redisson(redisson).build();
        log.info("Graph checkpoint ready: RedisSaver on {}:{}", host, port);
        return saver;
    }
}