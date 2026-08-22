package cn.lwx.lwxaiagent.admin;

import cn.lwx.lwxaiagent.canary.CanaryConfig;
import cn.lwx.lwxaiagent.canary.CanaryContext;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final GoldenSetRunner goldenSetRunner;
    private final AdminGuard adminGuard;
    private final CanaryConfig canaryConfig;

    public AdminController(GoldenSetRunner goldenSetRunner, AdminGuard adminGuard, CanaryConfig canaryConfig) {
        this.goldenSetRunner = goldenSetRunner;
        this.adminGuard = adminGuard;
        this.canaryConfig = canaryConfig;
    }

    @PostMapping("/golden-set/run")
    public Map<String, Object> runGoldenSet(HttpServletRequest request) {
        adminGuard.check(request);
        log.info("Golden Set evaluation triggered by admin");
        var report = goldenSetRunner.run();
        return Map.of(
                "passed", report.passed(),
                "total", report.total(),
                "rate", String.format("%.0f%%", report.rate() * 100),
                "verdict", report.verdict(),
                "details", report.results()
        );
    }

    /**
     * 查看灰度配置与当前用户桶位。
     */
    @GetMapping("/canary/status")
    public Map<String, Object> canaryStatus(HttpServletRequest request) {
        adminGuard.check(request);
        String userId = TenantContext.getUserId();
        int bucket = userId != null ? Math.abs(userId.hashCode() % 100) : -1;
        return Map.of(
                "percentage", canaryConfig.getPercentage(),
                "currentUserId", userId != null ? userId : "anonymous",
                "currentBucket", bucket,
                "isCanary", canaryConfig.isCanary(userId)
        );
    }
}
