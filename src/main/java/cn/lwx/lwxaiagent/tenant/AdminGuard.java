package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 管理端访问守卫（Phase 0 安全止血）。
 * <p>
 * 管理类接口（Token 签发、全量数据查询）通过两种方式之一放行：
 * <ol>
 *   <li>请求头 {@code X-Admin-Key} 与配置的 {@code admin.api-key}（环境变量 ADMIN_API_KEY）一致；</li>
 *   <li>当前 JWT 的 role 声明为 ADMIN（仅适用于经过 TenantInterceptor 的路径）。</li>
 * </ol>
 * 两种凭证都缺失或admin.api-key 未配置时，一律拒绝（fail-closed）。
 */
@Component
public class AdminGuard {

    private static final String ADMIN_HEADER = "X-Admin-Key";

    @Value("${admin.api-key:}")
    private String adminApiKey;

    /**
     * 校验管理端访问，不通过则抛出 403 业务异常。
     */
    public void check(HttpServletRequest request) {
        if (isAdminKeyValid(request)) {
            return;
        }
        if ("ADMIN".equals(TenantContext.getRole())) {
            return;
        }
        throw new BizException(403, "需要管理员权限");
    }

    private boolean isAdminKeyValid(HttpServletRequest request) {
        if (adminApiKey == null || adminApiKey.isBlank()) {
            return false;
        }
        return adminApiKey.equals(request.getHeader(ADMIN_HEADER));
    }
}
