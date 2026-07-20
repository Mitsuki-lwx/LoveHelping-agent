package cn.lwx.lwxaiagent.tenant;

import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器。
 * <p>
 * 在每个请求处理前，从 Authorization 头中提取 JWT，
 * 解析出 tenantId/userId/role，注入到 TenantContext（ThreadLocal）。
 * <p>
 * 无 token 的请求（如健康检查、Swagger）直接放行，不注入租户信息。
 * 有 token 但无效的请求返回 401。
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");

        // 无 token → 放行（不注入租户信息，业务代码需自行处理无租户场景）
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return true;
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = jwtTokenProvider.parseToken(token);
            jwtTokenProvider.injectContext(claims);
            log.debug("Tenant context set: tenant={}, user={}",
                    TenantContext.getTenantId(), TenantContext.getUserId());
            return true;
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid or expired token");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束清理 ThreadLocal，防止线程池复用导致数据泄漏
        TenantContext.clear();
    }
}
