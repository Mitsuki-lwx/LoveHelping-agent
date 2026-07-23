package cn.lwx.lwxaiagent.evolution.config;

import cn.lwx.lwxaiagent.evolution.SkillReflector;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class EvolutionConfig {

    @Bean(name = "evolutionExecutor")
    public Executor evolutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("evolution-");
        executor.setDaemon(true);
        return executor;
    }

    @Bean
    public SkillReflector skillReflector(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            EvolutionProperties props) {
        return new SkillReflector(chatModel, props.getQualityThreshold());
    }
}
