package cn.lwx.lwxaiagent.evolution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "evolution")
public class EvolutionProperties {
    /** Whether to enable the self-evolution mechanism */
    private boolean enabled = true;
    /** Force trigger reflection when session total duration exceeds this value (seconds), as safety net for extractDelaySeconds. Default 2 hours */
    private int idleTimeoutSeconds = 7200;
    /** Minimum skill quality score, skills below this threshold are not stored */
    private int qualityThreshold = 5;
    /** Delay after last message before triggering reflection (seconds) */
    private int extractDelaySeconds = 1800;
    /** Return top-K skills when retrieving */
    private int skillTopK = 3;
    /** Minimum similarity score for RAG retrieval */
    private double skillMinScore = 0.5;
}
