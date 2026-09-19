package cn.lwx.lwxaiagent.tenant.context;

/**
 * <h1>租户上下文 —— 基于 ThreadLocal 的请求级数据传递</h1>
 * <p>
 * 本类是多租户架构的<strong>数据隔离核心</strong>，通过 {@link ThreadLocal} 变量
 * 在当前请求线程中保存租户 ID、用户 ID 和角色信息，使得请求处理链路上的
 * 任何组件（Controller、Service、Repository 等）都能方便地获取当前请求的租户和用户身份，
 * 而无需逐层传递参数。
 * </p>
 *
 * <h2>多租户数据隔离原理</h2>
 * <p>
 * 在一个多租户（Multi-Tenant）系统中，多个组织/客户共享同一套应用实例和数据库。
 * 数据隔离是核心挑战。本系统采用"<strong>共享数据库 + 租户 ID 字段隔离</strong>"策略：
 * </p>
 * <ol>
 *   <li>每个请求携带着其所属租户的标识（从 JWT 中解析得到）</li>
 *   <li>{@link TenantContext} 在请求线程中存储该租户 ID</li>
 *   <li>所有数据库查询都带上租户 ID 条件，确保租户 A 无法看到租户 B 的数据</li>
 * </ol>
 *
 * <h2>ThreadLocal 的工作原理与风险</h2>
 * <p>
 * {@link ThreadLocal} 为每个线程提供独立的变量副本，线程之间互不干扰。
 * 在 Web 应用中，Tomcat 使用线程池处理请求：
 * </p>
 * <ul>
 *   <li><b>优势</b>：同一请求的整个处理链路（从 Filter 到 Controller 到 Service 到 Mapper）
 *       都由同一个线程执行，ThreadLocal 中的数据全程可用</li>
 *   <li><b>风险</b>：线程在请求结束后被<strong>归还线程池而非销毁</strong>，
 *       如果不清除 ThreadLocal，下一个复用该线程的请求可能会读到上个请求的残留数据，
 *       造成<strong>租户数据泄漏</strong>的严重安全漏洞</li>
 * </ul>
 * <p>
 * 因此，<strong>必须在每个请求结束时调用 {@link #clear()} 清理 ThreadLocal</strong>。
 * 本系统在 {@link cn.lwx.lwxaiagent.tenant.filter.TenantFilter} 的
 * {@code finally} 块中执行清理，确保无论请求处理是否成功，ThreadLocal 都会被清空。
 * </p>
 *
 * <h2>为什么不用 InheritableThreadLocal？</h2>
 * <p>
 * {@link InheritableThreadLocal} 会将父线程的值传递给子线程。在这个系统中<strong>不使用它</strong>：
 * </p>
 * <ul>
 *   <li>Spring 的 {@code @Async} 异步方法会复用线程池中的线程，
 *       如果使用 InheritableThreadLocal，子线程结束后同样存在清理问题</li>
 *   <li>线程池复用线程时，继承只发生在<b>线程创建</b>那一刻，池化后语义完全错乱</li>
 *   <li>在多线程并行处理场景下，需要更成熟的上下文传播方案
 *       （如 SLF4J MDC 或 Micrometer Context Propagation）</li>
 * </ul>
 *
 * <h2>跨线程边界规则（2026-09-19 立，勿违反）</h2>
 * <p>
 * <strong>这是 {@link ThreadLocal}，不跨线程。任何跨线程边界都必须显式传递租户身份，
 * 禁止依赖"自动继承"或"线程是同一个"这类错觉。</strong>现状与做法：
 * </p>
 * <table border="1">
 *   <tr><th>边界</th><th>做法</th></tr>
 *   <tr><td>HTTP 请求链</td><td>同线程，{@code TenantFilter}/{@code TenantInterceptor} 写入即可</td></tr>
 *   <tr><td>线程池 / 图执行</td><td>{@link #capture()} 取快照 → 执行线程 {@link #restore(Snapshot)}；
 *       或直接以<b>方法参数</b>传租户（优先，更显式）</td></tr>
 *   <tr><td>定时任务 / {@code @Async}</td><td>不读本类；租户身份从数据里取、或由调用方以参数传入</td></tr>
 * </table>
 * <p>
 * 反面情形：跨线程读 {@link #getTenantId()} 会<strong>静默返回 {@code null}</strong>（不抛异常），
 * 于是数据归属写错却没有任何报错。因此需要租户的异步代码，
 * 要么在入口 {@link #restore(Snapshot)}，要么把租户当参数传进来——没有第三种正确写法。
 * </p>
 *
 * <h2>存储的三个维度</h2>
 * <table border="1">
 *   <tr><th>变量</th><th>含义</th><th>来源</th><th>用途</th></tr>
 *   <tr><td>TENANT_ID</td><td>租户 ID</td><td>JWT 的 tenantId 声明</td><td>数据库查询条件、Redis 键前缀、日志标识</td></tr>
 *   <tr><td>USER_ID</td><td>用户 ID</td><td>JWT 的 sub 声明</td><td>数据归属标识、操作审计</td></tr>
 *   <tr><td>ROLE</td><td>角色</td><td>JWT 的 role 声明</td><td>权限控制（USER/ADMIN）</td></tr>
 * </table>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see cn.lwx.lwxaiagent.tenant.filter.TenantFilter ThreadLocal 清理过滤器
 * @see cn.lwx.lwxaiagent.tenant.interceptor.TenantInterceptor JWT 解析拦截器
 * @see ThreadLocal Java 核心类，提供线程局部变量
 */
public class TenantContext {

    /**
     * 存储当前请求的租户 ID。
     * 租户 ID 是多租户数据隔离的关键字段，所有数据库查询都需要带上此条件。
     */
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    /**
     * 存储当前请求的用户 ID（用户名）。
     * 用于标识当前操作是由哪个用户发起的，便于审计和数据归属判断。
     */
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    /**
     * 存储当前请求的用户角色。
     * 当前支持两种角色：{@code "USER"}（普通用户）和 {@code "ADMIN"}（管理员）。
     * 可用于实现方法级权限控制。
     */
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    /**
     * <h3>设置租户上下文信息</h3>
     * <p>
     * 将租户 ID、用户 ID 和角色一次性设置到当前线程的 ThreadLocal 中。
     * 通常在 JWT 验证通过后由拦截器调用。
     * </p>
     *
     * <h4>调用时机</h4>
     * <p>
     * 主要在 {@link cn.lwx.lwxaiagent.tenant.interceptor.TenantInterceptor#preHandle}
     * 方法中，JWT 解析成功后调用。
     * </p>
     *
     * @param tenantId 租户 ID，不能为 {@code null}（为 null 时后续 getTenantId() 将返回 null）
     * @param userId   用户 ID（用户名），不能为 {@code null}
     * @param role     用户角色，如 {@code "USER"} 或 {@code "ADMIN"}
     */
    public static void set(String tenantId, String userId, String role) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        ROLE.set(role);
    }

    /**
     * <h3>获取当前请求的租户 ID</h3>
     * <p>
     * 在请求处理链路的任何位置（Controller、Service、Mapper 等）调用此方法，
     * 即可获取当前请求所属的租户 ID，无需通过方法参数传递。
     * </p>
     *
     * <h4>典型用法</h4>
     * <pre>{@code
     * String tenantId = TenantContext.getTenantId();
     * if (tenantId == null) {
     *     tenantId = "default";  // 未登录用户使用默认租户
     * }
     * // 在数据库查询中带上租户 ID 条件
     * }</pre>
     *
     * @return 当前请求的租户 ID；如果当前请求未经过 JWT 认证（匿名请求），返回 {@code null}
     */
    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /**
     * <h3>获取当前请求的用户 ID</h3>
     * <p>
     * 返回当前请求的用户名，可用于数据归属判断和操作日志记录。
     * </p>
     *
     * @return 当前请求的用户 ID（用户名）；若未认证则返回 {@code null}
     */
    public static String getUserId() {
        return USER_ID.get();
    }

    /**
     * <h3>获取当前请求的用户角色</h3>
     * <p>
     * 返回当前用户的角色标识，可用于方法级权限判断。
     * </p>
     *
     * @return 当前用户的角色字符串（如 {@code "USER"} 或 {@code "ADMIN"}）；若未认证则返回 {@code null}
     */
    public static String getRole() {
        return ROLE.get();
    }

    /**
     * <h3>清理 ThreadLocal —— 防止内存泄漏和数据串扰</h3>
     * <p>
     * <strong>这是整个多租户安全体系中最关键的方法之一。</strong>
     * 必须在每个 HTTP 请求处理完成后调用，否则：
     * </p>
     * <ul>
     *   <li><b>数据泄漏</b>：Tomcat 线程池复用线程时，下一个请求可能读取到上一个请求的
     *       租户 ID 和用户 ID，导致租户 A 能访问租户 B 的数据</li>
     *   <li><b>内存泄漏</b>：ThreadLocal 的键是弱引用，但值不是。如果线程长期存活
     *       （如线程池中的线程），ThreadLocal 值会一直驻留在内存中，造成内存泄漏</li>
     * </ul>
     *
     * <h4>调用时机</h4>
     * <p>
     * 在 {@link cn.lwx.lwxaiagent.tenant.filter.TenantFilter#doFilterInternal} 的
     * {@code finally} 块中调用，无论请求处理成功还是异常，都能保证执行清理。
     * 同时也在 {@link cn.lwx.lwxaiagent.tenant.interceptor.TenantInterceptor#afterCompletion}
     * 中作为额外的安全保障。
     * </p>
     *
     * <h4>remove() vs set(null)</h4>
     * <p>
     * 使用 {@link ThreadLocal#remove()} 而非 {@code set(null)}：
     * {@code remove()} 会彻底删除条目，而 {@code set(null)} 只是将值设为 null，
     * ThreadLocalMap 中仍然保留该条目，仍可能造成微弱的内存泄漏。
     * </p>
     */
    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        ROLE.remove();
    }

    /**
     * <h3>租户上下文快照 —— 跨线程传递的载体</h3>
     * <p>
     * 不可变值对象。三个维度<b>允许同时为 {@code null}</b>（表示"当时没有租户身份"），
     * 这种快照 {@link #isEmpty()} 为 {@code true}，还原时等价于 {@link #clear()}。
     * </p>
     *
     * @param tenantId 提交线程当时的租户 ID，可为 {@code null}
     * @param userId   提交线程当时的用户 ID，可为 {@code null}
     * @param role     提交线程当时的角色，可为 {@code null}
     * @see #capture()
     */
    public record Snapshot(String tenantId, String userId, String role) {
        /** @return 三个维度是否全为 {@code null}（即"空快照"） */
        public boolean isEmpty() {
            return tenantId == null && userId == null && role == null;
        }
    }

    /**
     * <h3>在提交线程抓取上下文快照</h3>
     * <p>
     * 与 {@link #restore(Snapshot)} 配对使用，是跨线程边界（线程池、图执行）传递租户身份的
     * 标准姿势：提交线程 {@code capture()}，执行线程 {@code finally} 里 {@code restore()}。
     * </p>
     *
     * <h4>为什么必须先 capture 再执行</h4>
     * <p>
     * 若执行线程不先记下自己的旧值，任务结束后就无法把执行线程还原成原样，
     * 在线程池复用场景下会把本次任务的租户身份<b>泄漏给下一个任务</b>。
     * </p>
     *
     * @return 当前线程的上下文快照；线程无租户身份时返回空快照（非 {@code null}）
     */
    public static Snapshot capture() {
        return new Snapshot(TENANT_ID.get(), USER_ID.get(), ROLE.get());
    }

    /**
     * <h3>把快照还原到当前线程</h3>
     * <p>
     * 空快照（或 {@code null}）等价于 {@link #clear()}——会 {@code remove()} 而不是 {@code set(null)}，
     * 因此不会在 {@link ThreadLocalMap} 里留残留条目。
     * </p>
     * <p>
     * 必须在 {@code finally} 中调用：任务异常退出时若不还原，线程池复用时下一个任务会读到本次的租户身份。
     * </p>
     *
     * @param snapshot 由 {@link #capture()} 得到的快照；允许为 {@code null}
     */
    public static void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            clear();
            return;
        }
        set(snapshot.tenantId(), snapshot.userId(), snapshot.role());
    }
}
