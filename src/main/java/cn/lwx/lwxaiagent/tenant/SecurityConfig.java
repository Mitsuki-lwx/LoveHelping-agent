package cn.lwx.lwxaiagent.tenant;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Security + Web MVC 配置。
 * <p>
 * 设计原则（升级计划明确要求）：
 * - 不做配额、不做审计、不做用户管理
 * - JWT 解析 → 注入 TenantContext → 请求结束清理
 * - 所有接口默认放行（permitAll），认证逻辑由 TenantInterceptor 处理
 * <p>
 * 为什么不用 Spring Security 的 Authentication？
 * - 升级计划只要求租户隔离，不要求权限控制
 * - Spring Security 的 filter chain 太重，简单 JWT 用拦截器更轻量
 * - 引入 spring-boot-starter-security 只是为了 CSRF 关闭和 filter chain 控制
 */
@Slf4j
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    @Resource
    private TenantInterceptor tenantInterceptor;

    /**
     * 注册 TenantInterceptor，拦截需要认证的路径。
     * /auth/**（注册登录）和 /tenant/token（签发）不需要 token，不拦截。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/Love_app/**")
                .excludePathPatterns(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/actuator/**",
                        "/auth/**",
                        "/tenant/token"
                );
        log.info("TenantInterceptor registered for /Love_app/**");
    }

    /**
     * Spring Security Filter Chain 配置。
     * 放行所有请求，认证逻辑由 TenantInterceptor 处理。
     * 关闭 CSRF（REST API 不需要）。
     */
    @Bean
    public org.springframework.security.web.SecurityFilterChain securityFilterChain(
            org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
