package cn.lwx.lwxaiagent.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 租户清理 Filter。
 * <p>
 * 作用：确保请求结束后 ThreadLocal 一定被清理。
 * <p>
 * 为什么 TenantInterceptor 的 afterCompletion 不够？
 * - 拦截器的 afterCompletion 在异常情况下可能不被调用
 * - Filter 是 Servlet 容器级别，比拦截器更底层，能覆盖所有请求路径
 * - 双重保险：Interceptor 清一次 + Filter 清一次，确保不泄漏
 * <p>
 * 优先级设为最高（最高优先级最先执行），确保在所有业务逻辑之前就绑定好了上下文。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 无论请求成功还是异常，都清理 ThreadLocal
            TenantContext.clear();
        }
    }
}
