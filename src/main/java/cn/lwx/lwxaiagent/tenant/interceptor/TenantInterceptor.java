package cn.lwx.lwxaiagent.tenant.interceptor;

import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * <h1>租户认证拦截器 —— JWT 验证 + 租户上下文注入</h1>
 * <p>
 * 本拦截器是系统安全认证的<strong>核心执行者</strong>，在 Spring MVC 的请求处理流程中负责：
 * </p>
 * <ol>
 *   <li>从 HTTP 请求中提取 JWT Token（支持 Authorization 头和 URL 查询参数两种方式）</li>
 *   <li>验证 JWT 的签名和有效期（通过 {@link JwtTokenProvider}）</li>
 *   <li>将解析出的用户身份信息注入 {@link TenantContext}</li>
 * </ol>
 *
 * <h2>拦截器生命周期</h2>
 * <p>
 * Spring MVC 的 {@link HandlerInterceptor} 有三阶段回调：
 * </p>
 * <ol>
 *   <li><b>{@link #preHandle}</b>：在 Controller 方法执行前调用。
 *       本系统在此阶段进行 JWT 验证和上下文注入</li>
 *   <li><b>{@code postHandle}</b>：在 Controller 方法执行后、视图渲染前调用。
 *       本系统未实现此方法（不需要）</li>
 *   <li><b>{@link #afterCompletion}</b>：在整个请求完成（包括视图渲染）后调用。
 *       本系统在此阶段清理 ThreadLocal</li>
 * </ol>
 *
 * <h2>JWT 传递方式 —— 双重兼容策略</h2>
 * <p>
 * 本拦截器支持两种 JWT 传递方式，解决不同客户端的兼容性问题：
 * </p>
 *
 * <h3>方式一：Authorization 请求头（标准方式）</h3>
 * <pre>{@code
 * GET /Love_app/chat HTTP/1.1
 * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyXzAwMSJ9.xxx
 * }</pre>
 * <p>
 * 这是 HTTP 标准的 Bearer Token 认证方式，适用于普通 HTTP 请求（fetch/axios/OkHttp 等）。
 * </p>
 *
 * <h3>方式二：URL 查询参数（SSE 兼容方式）</h3>
 * <pre>{@code
 * GET /Love_app/agent/chat?token=eyJhbGciOiJIUzI1NiJ9.xxx&message=hello
 * }</pre>
 * <p>
 * <strong>这是专门为 SSE（Server-Sent Events）场景设计的兼容方案。</strong>
 * 浏览器的 {@link EventSource} API 不支持自定义请求头（无法设置 Authorization 头），
 * 因此只能通过 URL 查询参数传递 JWT。
 * </p>
 *
 * <h4>URL 传参的安全风险与缓解措施</h4>
 * <p>
 * 通过 URL 查询参数传递 Token 存在以下风险：
 * </p>
 * <ul>
 *   <li><b>服务器日志泄漏</b>：URL 通常会被访问日志（Nginx/Apache/Tomcat）记录，
 *       包括查询参数中的 Token</li>
 *   <li><b>浏览器历史泄漏</b>：URL 会保存在浏览器历史记录中</li>
 *   <li><b>Referer 头泄漏</b>：从当前页面跳转到外部链接时，Referer 头可能包含完整 URL</li>
 *   <li><b>缓解措施</b>：强烈建议<strong>生产环境全程使用 HTTPS</strong>，
 *       并配置日志过滤器屏蔽查询参数中的敏感信息</li>
 * </ul>
 *
 * <h2>匿名访问 vs 认证访问</h2>
 * <p>
 * 当请求中不包含任何 Token 时（两种方式都没有找到 JWT），本拦截器<strong>允许请求通过</strong>
 * （返回 {@code true}），不会阻塞匿名访问。此时 {@link TenantContext} 中的值为 {@code null}，
 * 下游组件（如 {@code ChatService}）会使用默认值（如 "default" 租户）。
 * </p>
 * <p>
 * 这种设计体现了"渐进式安全"的理念：系统默认开放，但通过 JWT 提供额外的
 * 身份识别和多租户隔离能力。对于不需要身份识别的公开 API，可以直接访问。
 * </p>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see JwtTokenProvider                         JWT 签发与验证
 * @see TenantContext                            租户上下文
 * @see cn.lwx.lwxaiagent.tenant.filter.TenantFilter ThreadLocal 清理过滤器
 * @see cn.lwx.lwxaiagent.tenant.config.SecurityConfig 拦截器注册配置
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    /**
     * JWT 令牌提供者，由 Spring 通过 {@link Resource @Resource} 注解注入。
     * 负责解析 JWT 字符串、验证签名、提取 Claims 并注入 TenantContext。
     */
    @Resource
    private JwtTokenProvider jwtTokenProvider;

    /**
     * <h3>请求前处理 —— JWT 提取、验证与上下文注入</h3>
     * <p>
     * 在 Controller 方法执行前调用。这是拦截器的核心逻辑。
     * 如果 JWT 验证失败，会直接向客户端返回 401 Unauthorized 响应，
     * 阻止请求进入 Controller。
     * </p>
     *
     * <h4>Token 提取优先级</h4>
     * <ol>
     *   <li>优先从 {@code Authorization} 请求头提取（去掉 "Bearer " 前缀）</li>
     *   <li>如果没有找到，则从 URL 查询参数 {@code token} 提取（SSE 兼容方案）</li>
     *   <li>如果两种方式都没有找到 Token，认为是匿名请求，直接放行</li>
     * </ol>
     *
     * <h4>验证失败处理</h4>
     * <p>
     * 当 JWT 验证失败时（签名错误、Token 过期、格式错误等），
     * 拦截器会：
     * </p>
     * <ol>
     *   <li>记录警告日志（包含失败原因，便于排查）</li>
     *   <li>设置 HTTP 状态码为 {@code 401 Unauthorized}</li>
     *   <li>向响应体写入错误消息 "Invalid or expired token"</li>
     *   <li>返回 {@code false}，阻止请求进入 Controller</li>
     * </ol>
     *
     * <h4>安全考量 —— 为什么不区分错误类型？</h4>
     * <p>
     * 无论 Token 是过期、签名错误还是格式错误，统一返回 "Invalid or expired token"。
     * 这是有意为之的安全设计：
     * </p>
     * <ul>
     *   <li>防止攻击者通过不同的错误消息探测系统信息（例如，区分"Token 格式错误"
     *       和"Token 签名错误"可能会暴露系统使用的加密方案）</li>
     *   <li>日志中保留了详细错误信息供运维人员排查，但对外只暴露模糊消息</li>
     * </ul>
     *
     * @param request  HTTP 请求对象，用于获取请求头和查询参数
     * @param response HTTP 响应对象，用于在验证失败时返回错误信息
     * @param handler  目标处理器（通常是 Controller 方法），可用于方法级注解判断
     * @return {@code true} 表示验证通过或匿名请求，继续执行后续拦截器和 Controller；
     *         {@code false} 表示验证失败，请求被拦截
     * @throws Exception 当 I/O 操作失败时（如写入错误响应时）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");
        String token = null;

        // 方式一：从 Authorization 请求头提取 Bearer Token
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);  // 去掉 "Bearer " 前缀（7 个字符）
        }

        // 方式二：从 URL 查询参数提取 Token（兼容 SSE，EventSource 不支持自定义请求头）
        if (token == null) {
            token = request.getParameter("token");
        }

        // 如果没有找到任何 Token，视为匿名请求，直接放行
        if (token == null) {
            return true;
        }

        try {
            // 解析 JWT 并验证签名和有效期
            Claims claims = jwtTokenProvider.parseToken(token);
            // 将解析出的租户信息注入 ThreadLocal 上下文
            jwtTokenProvider.injectContext(claims);
            log.debug("Tenant context set: tenant={}, user={}",
                    TenantContext.getTenantId(), TenantContext.getUserId());
            return true;
        } catch (Exception e) {
            // JWT 验证失败 —— 记录警告日志（含详细错误），但对外返回模糊消息
            log.warn("Invalid JWT token: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid or expired token");
            return false;  // 阻止请求进入 Controller
        }
    }

    /**
     * <h3>请求完成后处理 —— 二次清理 ThreadLocal</h3>
     * <p>
     * 在整个请求完成（包括 Controller 处理、视图渲染）后调用。
     * 调用 {@link TenantContext#clear()} 清理当前线程的 ThreadLocal 数据。
     * </p>
     *
     * <h4>为什么已经有了 TenantFilter 还需要这里再清理？</h4>
     * <p>
     * 这是<strong>纵深防御</strong>（Defense in Depth）策略的体现：
     * </p>
     * <ul>
     *   <li>{@link cn.lwx.lwxaiagent.tenant.filter.TenantFilter} 的 {@code finally}
     *       块是<strong>主防线</strong>，在过滤器链的最外层保证清理</li>
     *   <li>本方法是<strong>补充防线</strong>，在拦截器层面提供额外的安全保障。
     *       即使因某些极端情况（如自定义的 Filter 绕过了 TenantFilter），
     *       这里的清理仍然会被执行</li>
     *   <li>双重清理是安全的，因为 {@link ThreadLocal#remove()} 是幂等操作 ——
     *       对同一个 ThreadLocal 多次调用 remove 不会抛出异常</li>
     * </ul>
     *
     * <h4>调用时机</h4>
     * <p>
     * 在 {@link #preHandle} 返回 {@code true} 的前提下：
     * </p>
     * <ul>
     *   <li>如果 preHandle 返回 {@code false}（验证失败），本方法不会被调用</li>
     *   <li>如果 preHandle 返回 {@code true}，无论 Controller 是否抛出异常，
     *       本方法都<strong>一定会</strong>被调用</li>
     * </ul>
     *
     * @param request  HTTP 请求对象
     * @param response HTTP 响应对象
     * @param handler  目标处理器
     * @param ex       如果在 Controller 处理过程中抛出了异常，此参数为非 {@code null}；
     *                 如果正常完成，此参数为 {@code null}
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
