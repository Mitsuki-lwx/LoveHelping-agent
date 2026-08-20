package cn.lwx.lwxaiagent.tenant.filter;

import cn.lwx.lwxaiagent.tenant.context.TenantContext;
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
 * <h1>租户上下文清理过滤器 —— ThreadLocal 的"安全网"</h1>
 * <p>
 * 本过滤器是系统多租户安全体系的<strong>最后一道防线</strong>，职责极其简单但至关重要：
 * 在每个 HTTP 请求处理完成后（无论成功还是异常），<strong>强制清理
 * {@link TenantContext} 中的所有 ThreadLocal 数据</strong>。
 * </p>
 *
 * <h2>为什么需要这个过滤器？</h2>
 * <p>
 * 在 Tomcat 的线程池模型下，线程处理完一个请求后不会被销毁，而是归还线程池等待复用。
 * 如果 ThreadLocal 中的租户数据没有被清理，下一个复用该线程的请求可能会：
 * </p>
 * <ul>
 *   <li><b>读到错误的租户 ID</b>：将租户 A 的请求数据写入租户 B 的数据库记录中，
 *       造成严重的数据泄漏和污染</li>
 *   <li><b>读到错误的用户 ID</b>：操作审计日志中出现错误的用户归属</li>
 *   <li><b>内存泄漏</b>：ThreadLocal 中的值对象被线程长期持有，无法被 GC 回收</li>
 * </ul>
 *
 * <h2>ThreadLocal 清理的三个保障层次</h2>
 * <p>
 * 本系统采用了<strong>三道防线</strong>确保 ThreadLocal 一定被清理：
 * </p>
 * <ol>
 *   <li><b>TenantFilter（本类）</b>：Servlet 过滤器级别，使用最高优先级（{@code HIGHEST_PRECEDENCE}）
 *       最先执行。在 {@code finally} 块中执行清理，无论后续处理成功还是异常</li>
 *   <li><b>TenantInterceptor#afterCompletion</b>：Spring 拦截器级别，
 *       在视图渲染完成后执行清理，作为补充保障</li>
 *   <li><b>finally 块</b>：本过滤器使用 Java 的 {@code finally} 机制，
 *       这是 Java 语言层面最强的保证 —— 即使发生了未捕获的异常或请求被中断，
 *       {@code finally} 块中的清理代码也一定会执行</li>
 * </ol>
 *
 * <h2>过滤器 vs 拦截器的执行顺序</h2>
 * <p>
 * 在 Spring MVC 框架中，请求处理的执行顺序如下：
 * </p>
 * <pre>{@code
 * 请求到达
 *   → TenantFilter.doFilterInternal()  // Servlet 过滤器（最先执行）
 *     → TenantInterceptor.preHandle()    // Spring 拦截器（JWT 解析，设置上下文）
 *       → Controller 处理业务逻辑
 *     → TenantInterceptor.afterCompletion()  // 拦截器清理（第三道防线）
 *   → TenantFilter.doFilterInternal() 的 finally 块  // 过滤器清理（最后执行）
 * 请求结束
 * }</pre>
 *
 * <h2>为什么使用 OncePerRequestFilter？</h2>
 * <p>
 * 继承 {@link OncePerRequestFilter} 而非直接实现 {@link jakarta.servlet.Filter}：
 * </p>
 * <ul>
 *   <li>{@code OncePerRequestFilter} 保证在一次请求中<strong>只执行一次</strong>，
 *       即使在请求处理过程中发生了 forward 或 include，过滤器也不会重复执行</li>
 *   <li>避免了因多次清理导致的不必要开销（虽然清理是幂等的，但没必要做多次）</li>
 * </ul>
 *
 * <h2>执行顺序注解 @Order</h2>
 * <p>
 * {@code @Order(Ordered.HIGHEST_PRECEDENCE)} 确保本过滤器<strong>在所有过滤器中第一个执行</strong>
 * （同时也是最后一个完成，因为过滤器链是嵌套调用的）。
 * 这保证了 ThreadLocal 清理覆盖整个请求处理周期。
 * </p>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see TenantContext               租户上下文（需要被清理的目标）
 * @see cn.lwx.lwxaiagent.tenant.interceptor.TenantInterceptor JWT 认证拦截器
 * @see OncePerRequestFilter        Spring 的单次执行过滤器基类
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    /**
     * <h3>过滤器核心逻辑 —— 包装请求处理 + finally 清理</h3>
     * <p>
     * 本方法<strong>不修改请求或响应</strong>，也不进行任何认证判断。
     * 它唯一的职责是：在请求处理完成后，调用 {@link TenantContext#clear()} 清理 ThreadLocal。
     * </p>
     *
     * <h4>finally 块的可靠性</h4>
     * <p>
     * Java 的 {@code finally} 块在所有场景下都会执行，包括：
     * </p>
     * <ul>
     *   <li>正常返回</li>
     *   <li>抛出异常（包括 {@link Error} 级别的严重错误）</li>
     *   <li>方法内部的 {@code return} 语句</li>
     * </ul>
     * <p>
     * 唯一不执行的情况是 JVM 崩溃或调用 {@code System.exit()} 或线程被 {@code kill -9} 强制终止，
     * 这些情况下 ThreadLocal 泄漏已经不重要了（整个 JVM 进程都没了）。
     * </p>
     *
     * <h4>为什么不在 try 之前做任何事？</h4>
     * <p>
     * 本方法刻意不包含任何 try 之前的业务逻辑。设置 ThreadLocal 的职责
     * 由 {@link cn.lwx.lwxaiagent.tenant.interceptor.TenantInterceptor#preHandle}
     * 承担。本过滤器只负责"兜底清理"，职责单一、逻辑简单，出错概率极低。
     * </p>
     *
     * @param request     HTTP 请求对象，由 Servlet 容器创建
     * @param response    HTTP 响应对象，由 Servlet 容器创建
     * @param filterChain 过滤器链，用于将请求传递给下一个过滤器或目标 Servlet
     * @throws ServletException 当过滤器链中的后续过滤器或 Servlet 抛出 Servlet 异常时
     * @throws IOException      当请求或响应的 I/O 操作失败时
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        try {
            // 将请求传递给过滤器链中的下一个组件
            // （可能是其他过滤器、拦截器、或最终的 Controller）
            filterChain.doFilter(request, response);
        } finally {
            // 【关键】无论处理成功还是异常，都必须清理 ThreadLocal
            // 防止线程池中的线程被复用时发生数据串扰
            TenantContext.clear();
        }
    }
}
