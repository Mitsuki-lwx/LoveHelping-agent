package cn.lwx.lwxaiagent.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.memory")
public class MemoryProperties {

    /** 普通对话记忆窗口大小（条消息） */
    private int windowSize = 20;

    /** Agent 对话记忆窗口大小（LoveManus 用，需要更大） */
    private int agentWindowSize = 50;

    /** 记忆策略：当前只支持 JDBC，预留 REDIS / FILE */
    private String strategy = "JDBC";
}
