package cn.lwx.lwxaiagent.evolution;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(EvolutionProperties.class)
public class EvolutionConfig {

    private final JdbcTemplate jdbc;

    public EvolutionConfig(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_vote (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
                session_id VARCHAR(100) NOT NULL,
                message_index INT NOT NULL,
                vote_type VARCHAR(10) NOT NULL,
                feedback_text VARCHAR(500) DEFAULT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_entry (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
                source_session_id VARCHAR(100) DEFAULT NULL,
                entry_type VARCHAR(20) NOT NULL,
                label VARCHAR(10) NOT NULL DEFAULT 'GOOD',
                title VARCHAR(200) NOT NULL,
                content TEXT NOT NULL,
                tags VARCHAR(500) DEFAULT NULL,
                weight INT DEFAULT 1,
                quality_score TINYINT DEFAULT 5,
                is_active BOOLEAN DEFAULT TRUE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            )
        """);
        log.info("Evolution tables initialized");
    }

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
    public ConversationExtractor conversationExtractor(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            EvolutionProperties props) {
        return new ConversationExtractor(chatModel, props.getQualityThreshold());
    }

    @Bean
    public TaskScheduler evolutionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("evolution-sched-");
        scheduler.setDaemon(true);
        return scheduler;
    }
}
