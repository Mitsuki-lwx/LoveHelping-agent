package cn.lwx.lwxaiagent.tenant.config;

import cn.lwx.lwxaiagent.tenant.interceptor.TenantInterceptor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <h1>安全配置类 —— 拦截器注册与 Spring Security 配置</h1>
 * <p>
 * 本类是系统安全策略的集中配置入口，承担两个职责：
 * </p>
 * <ol>
 *   <li><b>注册自定义拦截器</b>（{@link TenantInterceptor}）：
 *       对受保护的 API 路径进行 JWT 身份认证和多租户上下文注入</li>
 *   <li><b>配置 Spring Security 过滤器链</b>：
 *       禁用 CSRF 保护，开放所有请求路径（认证逻辑由自定义拦截器处理而非 Spring Security）</li>
 * </ol>
 *
 * <h2>为什么这样设计？</h2>
 * <p>
 * 本系统采用了"<strong>轻量级自研拦截器 + 最小化 Spring Security</strong>"的混合安全架构：
 * </p>
 * <ul>
 *   <li><b>Spring Security</b> 仅用于禁用 CSRF 和开放所有路径（全部 permitAll）</li>
 *   <li><b>自定义 TenantInterceptor</b> 负责实际的 JWT 解析和身份认证</li>
 * </ul>
 * <p>
 * 这种设计的优势：
 * </p>
 * <ul>
 *   <li>避免了 Spring Security 复杂的配置体系（SecurityFilterChain、AuthenticationProvider 等）</li>
 *   <li>认证逻辑完全自控，便于调试和扩展</li>
 *   <li>对于 AI Agent 这种 API 驱动的应用，Spring Security 的"表单登录"、"Session 管理"等特性用不上</li>
 * </ul>
 *
 * <h2>拦截器 vs 过滤器的选择</h2>
 * <p>
 * 本系统同时使用了 {@link cn.lwx.lwxaiagent.tenant.filter.TenantFilter}（Servlet 过滤器）和
 * {@link TenantInterceptor}（Spring 拦截器）。两者的区别和分工：
 * </p>
 * <ul>
 *   <li><b>TenantFilter</b>：最高优先级执行，只做一件事 —— 在请求处理完成后清理 ThreadLocal，
 *       防止内存泄漏。因为过滤器在 Servlet 层面运行，在所有拦截器之前和之后执行</li>
 *   <li><b>TenantInterceptor</b>：在 Spring MVC 层面运行，负责 JWT 解析和上下文注入。
 *       可以精细控制哪些路径需要拦截、哪些路径放行</li>
 * </ul>
 *
 * <h2>CSRF 保护为何被禁用？</h2>
 * <p>
 * CSRF（Cross-Site Request Forgery，跨站请求伪造）攻击的前提是：
 * 浏览器会自动携带目标站点的 Cookie。本系统的 API 使用 JWT（通过 Authorization 请求头传递），
 * 而非 Cookie-Session 机制，因此<strong>不存在 CSRF 攻击向量</strong>。
 * 启用 CSRF 保护反而会导致 POST/PUT/DELETE 等请求被无意义拦截。
 * </p>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see TenantInterceptor   JWT 认证拦截器
 * @see cn.lwx.lwxaiagent.tenant.filter.TenantFilter ThreadLocal 清理过滤器
 * @see WebMvcConfigurer    Spring MVC 配置接口
 */
@Slf4j
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    /**
     * 租户拦截器，由 Spring 通过 {@link Resource @Resource} 注解注入。
     * 负责从 HTTP 请求的 Authorization 头中提取 JWT、验证签名、解析用户信息、
     * 并将租户上下文注入到 {@link cn.lwx.lwxaiagent.tenant.context.TenantContext}。
     */
    @Resource
    private TenantInterceptor tenantInterceptor;

    /**
     * <h3>注册拦截器 —— 配置哪些路径需要 JWT 认证</h3>
     * <p>
     * 这是 Spring MVC 拦截器配置的核心方法，通过 {@link InterceptorRegistry} 注册
     * {@link TenantInterceptor}，并精确控制拦截范围。
     * </p>
     *
     * <h4>受保护的路径（需要携带有效 JWT）</h4>
     * <ul>
     *   <li>{@code /Love_app/**}：AI 聊天相关的所有 API（发送消息、流式聊天、Agent 会话等）</li>
     *   <li>{@code /memory/**}：对话记忆管理 API</li>
     *   <li>{@code /evolution/**}：进化/反馈相关的 API（投票、知识管理）</li>
     *   <li>{@code /auth/me}：获取当前用户信息的 API</li>
     * </ul>
     *
     * <h4>公开路径（无需 JWT，直接放行）</h4>
     * <ul>
     *   <li>{@code /swagger-ui/**}：Swagger API 文档界面</li>
     *   <li>{@code /v3/api-docs/**}：OpenAPI 规范文档 JSON</li>
     *   <li>{@code /actuator/**}：Spring Boot Actuator 健康检查和监控端点</li>
     *   <li>{@code /auth/login}：用户登录接口（登录前当然没有 Token）</li>
     *   <li>{@code /auth/register}：用户注册接口（注册前也没有 Token）</li>
     * </ul>
     *
     * <h4>安全考量</h4>
     * <p>
     * 注意 Actuator 端点（{@code /actuator/**}）在生产环境中暴露可能带来安全风险。
     * 虽然此处排除了 JWT 认证，但建议额外通过网络层（防火墙/反向代理）限制访问，
     * 或者通过 Spring Security 限制只有特定 IP 可以访问。
     * </p>
     *
     * @param registry Spring MVC 拦截器注册表，用于添加拦截器并配置路径匹配规则
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/Love_app/**", "/memory/**", "/evolution/**", "/media/**", "/auth/me")
                .excludePathPatterns(
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/actuator/**",
                        "/auth/login",
                        "/auth/register"
                );
        log.info("TenantInterceptor registered for /Love_app/**, /memory/**, /evolution/**, /auth/me");
    }

    /**
     * <h3>配置 Spring Security 过滤器链</h3>
     * <p>
     * 创建一个最小化的 Spring Security 过滤器链 Bean。
     * 因为本系统的实际认证逻辑由自定义的 {@link TenantInterceptor} 处理，
     * 所以这里只需要：
     * </p>
     * <ol>
     *   <li><b>禁用 CSRF 保护</b>：
     *       因为系统使用 JWT（通过 Authorization 请求头）而非 Cookie-Session 机制，
     *       不存在 CSRF 攻击向量，启用反而会无意义拦截合法请求</li>
     *   <li><b>开放所有请求</b>：
     *       所有路径设为 {@code permitAll()}，不做 Spring Security 层面的权限控制。
     *       实际的认证和授权由 {@link TenantInterceptor} 处理</li>
     * </ol>
     *
     * <h4>为什么使用 Spring Security 而不是完全移除它？</h4>
     * <p>
     * 虽然不使用 Spring Security 的认证授权功能，但保留其过滤器链有以下好处：
     * </p>
     * <ul>
     *   <li>集成 Spring 生态中的其他安全相关功能（如 PasswordEncoder 自动配置）</li>
     *   <li>未来如需引入更复杂的安全策略（如 OAuth2、方法级安全注解），
     *       不需要从零开始集成 Spring Security</li>
     *   <li>Spring Security 默认提供了一些安全 HTTP 头（如 X-Content-Type-Options 等），
     *       即使不显式配置也能增强应用安全性</li>
     * </ul>
     *
     * @param http Spring Security 的 HTTP 安全配置构建器，
     *             用于配置 CSRF、授权规则、表单登录等
     * @return 构建完成的 {@link org.springframework.security.web.SecurityFilterChain} Bean
     * @throws Exception 当安全配置构建过程中发生错误时抛出
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
