package cn.lwx.lwxaiagent.canary;

import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 灰度拦截器：判断当前用户是否在灰度桶内，设置 CanaryContext，
 * 并在响应头添加 X-Canary 标识（前端/网关可据此分流）。
 */
@Slf4j
@Component
public class CanaryInterceptor implements HandlerInterceptor {

    private final CanaryConfig config;

    public CanaryInterceptor(CanaryConfig config) {
        this.config = config;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = TenantContext.getUserId();
        boolean canary = config.isCanary(userId);
        CanaryContext.set(canary);

        int bucket = -1;
        if (userId != null && !userId.isBlank()) {
            bucket = Math.abs(userId.hashCode() % 100);
            CanaryContext.setBucket(bucket);
        }

        response.setHeader("X-Canary", canary ? "true" : "false");
        return true; // 不阻断请求，仅标记
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CanaryContext.clear();
        CanaryContext.clearBucket();
    }
}
