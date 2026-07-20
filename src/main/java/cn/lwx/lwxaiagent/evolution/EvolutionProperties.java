package cn.lwx.lwxaiagent.evolution;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "evolution")
public class EvolutionProperties {
    private boolean enabled = true;
    private int idleTimeoutSeconds = 300;
    private int qualityThreshold = 5;
    private int extractDelaySeconds = 30;
}
