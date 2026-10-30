package cn.lwx.lwxaiagent.analytics;

import cn.lwx.lwxaiagent.audit.AuditLog;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <h3>运营分析端点（管理端，X-Admin-Key 保护）</h3>
 *
 * <p>{@code GET /admin/analytics/overview?days=30} —— PM 看板的唯一数据出口。</p>
 *
 * <p>⛔ 隐私底线：只出计数，绝不出消息正文/用户名；测试账号排除数如实上报。</p>
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final AdminGuard adminGuard;

    public AnalyticsController(AnalyticsService analyticsService, AdminGuard adminGuard) {
        this.analyticsService = analyticsService;
        this.adminGuard = adminGuard;
    }

    /** 总览看板：days 越界由 service 夹到 [1,90]。 */
    @AuditLog("analytics_overview")
    @GetMapping("/analytics/overview")
    public Result<AnalyticsOverviewVO> overview(HttpServletRequest request,
            @RequestParam(defaultValue = "30") int days) {
        adminGuard.check(request);
        return Result.ok(analyticsService.overview(days));
    }
}
