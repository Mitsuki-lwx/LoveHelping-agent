package cn.lwx.lwxaiagent.canary;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 灰度配置（08 §3）：固定百分位桶，userId hashCode % 100 映射。
 * 通过环境变量 CANARY_PERCENTAGE 覆盖。
 */
@ConfigurationProperties(prefix = "app.canary")
public class CanaryConfig {

    /**
     * 灰度百分比（0-100）。0=不灰度（全量旧版本），100=全量新版本。
     * 通过环境变量 CANARY_PERCENTAGE 覆盖。
     */
    private int percentage = 0;

    public int getPercentage() { return percentage; }
    public void setPercentage(int percentage) { this.percentage = Math.max(0, Math.min(100, percentage)); }

    /**
     * 用户是否在灰度桶内。
     * @param userId 用户标识
     * @return true 如果该用户的桶位 < percentage
     */
    public boolean isCanary(String userId) {
        if (percentage <= 0 || userId == null || userId.isBlank()) return false;
        if (percentage >= 100) return true;
        int bucket = Math.abs(userId.hashCode() % 100);
        return bucket < percentage;
    }
}
