package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.audit.AuditLog;
import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.service.ComplaintService;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import cn.lwx.lwxaiagent.vo.ComplaintVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <h1>投诉举报控制器（V34 合规）</h1>
 *
 * <p>统一 {@link Result} 包装；错误一律 {@link BizException}
 * （由 {@code GlobalExceptionHandler} 转成同形状响应）。</p>
 *
 * <h2>四个接口</h2>
 * <ol>
 *   <li>{@code POST /complaint} —— 需登录，提交投诉（24 小时内 ≤5 条）；</li>
 *   <li>{@code GET /complaint/mine} —— 需登录，自己的投诉（时间倒序）；</li>
 *   <li>{@code GET /complaint/admin/list} —— 管理端（可跨用户，带 userId）；</li>
 *   <li>{@code POST /complaint/admin/{id}/status} —— 管理端更新处理状态。</li>
 * </ol>
 *
 * <p>路径注册见 {@code SecurityConfig}：{@code /complaint/**} 走 JWT 拦截器；
 * 管理端两个接口再经 {@link AdminGuard#check} 二次鉴权。</p>
 */
@Slf4j
@RestController
@RequestMapping("/complaint")
public class ComplaintController {

    private final ComplaintService complaintService;
    private final AdminGuard adminGuard;

    public ComplaintController(ComplaintService complaintService, AdminGuard adminGuard) {
        this.complaintService = complaintService;
        this.adminGuard = adminGuard;
    }

    /**
     * 提交投诉（需登录）。
     *
     * <p>请求：{@code {"category":"CONTENT","content":"...","contact":"可选"}}</p>
     * <p>响应：{@code {"code":200,"message":"success","data":"<投诉 id>"}}</p>
     */
    @AuditLog("complaint_submit")
    @PostMapping
    public Result<String> submit(@RequestBody(required = false) Map<String, String> body) {
        String userId = requireLogin();
        Map<String, String> b = body == null ? Map.of() : body;
        String id = complaintService.submit(userId, TenantContext.getTenantId(),
                b.get("category"), b.get("content"), b.get("contact"));
        return Result.ok(id);
    }

    /** 我的投诉（需登录，时间倒序）。 */
    @GetMapping("/mine")
    public Result<List<ComplaintVO>> mine() {
        String userId = requireLogin();
        return Result.ok(complaintService.listMine(userId));
    }

    /**
     * 管理端列表（可跨用户）。
     *
     * @param status 可选：OPEN/PROCESSING/CLOSED
     * @param limit  单页条数，默认 50、上限 200
     */
    @GetMapping("/admin/list")
    public Result<List<ComplaintVO>> adminList(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) Integer limit,
                                               HttpServletRequest request) {
        adminGuard.check(request);
        if (status != null && !status.isBlank() && !ComplaintService.STATUSES.contains(status)) {
            throw new BizException(400, "status 只能为 OPEN/PROCESSING/CLOSED");
        }
        return Result.ok(complaintService.listForAdmin(status, limit));
    }

    /** 管理端更新处理状态。 */
    @AuditLog("complaint_status")
    @PostMapping("/admin/{id}/status")
    public Result<String> updateStatus(@PathVariable Long id, @RequestParam String status,
                                       HttpServletRequest request) {
        adminGuard.check(request);
        complaintService.updateStatus(id, status);
        return Result.ok("ok");
    }

    /** 需登录接口的未登录兜底（拦截器对匿名请求放行，这里是纵深防御）。 */
    private String requireLogin() {
        String userId = TenantContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
