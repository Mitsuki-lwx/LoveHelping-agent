package cn.lwx.lwxaiagent.admin;

import cn.lwx.lwxaiagent.tenant.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 管理端点：Golden Set 评估、Prompt 版本管理。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final GoldenSetRunner goldenSetRunner;
    private final AdminGuard adminGuard;

    public AdminController(GoldenSetRunner goldenSetRunner, AdminGuard adminGuard) {
        this.goldenSetRunner = goldenSetRunner;
        this.adminGuard = adminGuard;
    }

    /**
     * 运行 Golden Set 回归评估（需 ADMIN 权限）。
     * 返回通过率、每条用例评分、门禁判定（PASS/REVIEW/FAIL）。
     */
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
}
