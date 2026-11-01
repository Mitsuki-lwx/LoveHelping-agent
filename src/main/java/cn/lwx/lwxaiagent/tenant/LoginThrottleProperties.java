package cn.lwx.lwxaiagent.tenant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 登录失败节流配置（change security-hardening / task 4）。
 *
 * <p>两个维度独立计数：<b>账号</b>（防止专打一个账号）与<b>来源 IP</b>（防止换账号横向撞库）。</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.auth.login-throttle")
public class LoginThrottleProperties {

    /** 总开关。关闭后登录失败不再计数，启动日志会 WARN。 */
    private boolean enabled = true;

    /** 账号维度：窗口内允许的连续失败次数，达到即开始退避。 */
    @Min(1) @Max(100) private int maxAttempts = 5;

    /** IP 维度：窗口内允许的失败次数（通常比账号维度宽松）。 */
    @Min(1) @Max(1000) private int maxIpAttempts = 20;

    /** 计数窗口（秒）。 */
    @Min(60) @Max(86400) private int windowSeconds = 900;

    /** 首次退避秒数；之后按失败次数指数增长。 */
    @Min(1) @Max(3600) private int baseBackoffSeconds = 30;

    /** 退避上限（秒）。 */
    @Min(1) @Max(86400) private int maxBackoffSeconds = 900;
}
