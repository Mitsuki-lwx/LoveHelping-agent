package cn.lwx.lwxaiagent.evolution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "evolution")
public class EvolutionProperties {
    /** 是否启用自我进化机制 */
    private boolean enabled = true;
    /** 会话总时长超过此值（秒）强制触发反思，作为 extractDelaySeconds 的兜底。默认 2 小时 */
    private int idleTimeoutSeconds = 7200;
    /** skill 最低质量分，低于此分不存储 */
    private int qualityThreshold = 5;
    /** 最后一次消息后延迟多久触发反思（秒） */
    private int extractDelaySeconds = 1800;
    /** 检索时返回 top-K 条 skill */
    private int skillTopK = 3;
    /** RAG 检索时最低相似度分数 */
    private double skillMinScore = 0.5;
}
