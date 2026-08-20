package cn.lwx.lwxaiagent.infrastructure.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LlmGateway 配置（ADR-7）：重试与降级参数。
 */
@Data
@ConfigurationProperties(prefix = "app.llm")
public class LlmGatewayProperties {

    /** 主模型调用失败重试（指数退避） */
    private Retry retry = new Retry();

    /** 主模型全败后是否切换备用供应商 */
    private boolean fallbackEnabled = true;

    @Data
    public static class Retry {
        /** 主模型最多尝试次数（含首次） */
        private int maxAttempts = 3;
        /** 指数退避初始间隔（毫秒），后续按 2 倍递增 */
        private long backoffMs = 1000;
    }
}
