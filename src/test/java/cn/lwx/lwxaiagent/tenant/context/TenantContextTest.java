package cn.lwx.lwxaiagent.tenant.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>TenantContext（租户上下文）单元测试</h1>
 *
 * <p>测试 {@link TenantContext} 的核心行为，这是一个基于 {@link ThreadLocal} 的租户信息存储类：</p>
 * <ul>
 *   <li>每个 HTTP 请求的线程独立持有自己的租户信息</li>
 *   <li>不同线程之间的 TenantContext 互不干扰（线程隔离）</li>
 *   <li>请求处理完成后需要调用 clear() 清理，避免内存泄漏</li>
 * </ul>
 *
 * <p><b>为什么用 ThreadLocal？</b>在 Web 应用中，一个请求由一个线程处理。
 * Filter/Interceptor 将 JWT 解析出的租户信息存入 ThreadLocal，
 * 后续的 Controller/Service 可以直接从 TenantContext 获取，无需层层传参。</p>
 */
class TenantContextTest {

    /**
     * 测试正常的 set → get 流程
     *
     * <p>设置 tenantId、userId、role 后，立即获取应该返回相同的值</p>
     */
    @Test
    void setAndGet() {
        TenantContext.set("tenant1", "user1", "ADMIN");
        assertEquals("tenant1", TenantContext.getTenantId());  // 租户 ID 应正确
        assertEquals("user1", TenantContext.getUserId());      // 用户 ID 应正确
        assertEquals("ADMIN", TenantContext.getRole());        // 角色应正确
        TenantContext.clear();  // 清理，避免影响其他测试
    }

    /**
     * 测试 clear() 清空所有值
     *
     * <p>调用 clear() 后，所有 getter 应返回 null</p>
     */
    @Test
    void clearRemovesValues() {
        TenantContext.set("t", "u", "r");
        TenantContext.clear();
        assertNull(TenantContext.getTenantId());   // 清空后应为 null
        assertNull(TenantContext.getUserId());     // 清空后应为 null
        assertNull(TenantContext.getRole());       // 清空后应为 null
    }

    /**
     * 测试初始状态：在没有任何 set 操作前，所有 getter 应返回 null
     */
    @Test
    void defaultsAreNull() {
        assertNull(TenantContext.getTenantId());   // 初始值应为 null
        assertNull(TenantContext.getUserId());     // 初始值应为 null
        assertNull(TenantContext.getRole());       // 初始值应为 null
    }

    /**
     * <b>核心测试：验证线程隔离</b>
     *
     * <p>这个测试验证了 ThreadLocal 的最关键特性——不同线程的数据互不影响：</p>
     * <ol>
     *   <li>主线程设置自己的租户信息（"main"）</li>
     *   <li>子线程启动后，TenantContext 是空的（不是 "main"）——证明隔离</li>
     *   <li>子线程设置自己的信息（"thread"），读取也是 "thread"</li>
     *   <li>子线程结束后，主线程的 TenantContext 仍然是 "main"——互不干扰</li>
     * </ol>
     *
     * <p>这个特性是多租户系统的基础：即使多个用户的请求被同一个 Tomcat 线程池处理，
     * 每个线程的租户信息也不会互相污染。</p>
     */
    @Test
    void threadIsolation() throws InterruptedException {
        // 主线程设置
        TenantContext.set("main", "mainUser", "USER");

        // 创建子线程
        Thread t = new Thread(() -> {
            // 子线程中：TenantContext 应该是空的（不是 "main"）
            assertNull(TenantContext.getTenantId());
            // 子线程设置自己的信息
            TenantContext.set("thread", "threadUser", "ADMIN");
            assertEquals("thread", TenantContext.getTenantId());  // 子线程读自己的
        });
        t.start();
        t.join();  // 等待子线程结束

        // 子线程结束后：主线程的 TenantContext 还是 "main"，没有被污染
        assertEquals("main", TenantContext.getTenantId());
        TenantContext.clear();
    }

    /**
     * <b>跨线程原语：capture / restore 往返</b>
     *
     * <p>提交线程抓快照，执行线程还原——这是线程池边界传递租户身份的标准姿势。</p>
     */
    @Test
    void captureAndRestoreRoundTrip() {
        TenantContext.set("t1", "u1", "USER");
        TenantContext.Snapshot snapshot = TenantContext.capture();

        TenantContext.set("t2", "u2", "ADMIN");  // 模拟执行线程上的另一个任务
        assertEquals("t2", TenantContext.getTenantId());

        TenantContext.restore(snapshot);
        assertEquals("t1", TenantContext.getTenantId());
        assertEquals("u1", TenantContext.getUserId());
        assertEquals("USER", TenantContext.getRole());
        TenantContext.clear();
    }

    /**
     * <b>空快照还原 == clear</b>（而不是 set(null) 留残留条目）
     *
     * <p>场景：线程池复用时，任务收尾必须把本线程上被写入的租户身份清干净，
     * 否则下一个任务会读到上一个任务的租户。</p>
     */
    @Test
    void restoreOfEmptySnapshotClearsInsteadOfLeavingResidue() {
        TenantContext.clear();
        TenantContext.Snapshot empty = TenantContext.capture();
        assertTrue(empty.isEmpty());

        TenantContext.set("leaked", "leakedUser", "ADMIN");
        TenantContext.restore(empty);

        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
        assertNull(TenantContext.getRole());
    }

    /** 防御性：snapshot 为 null 时按空快照处理，不得抛 NPE（finally 块里最怕再抛异常）。 */
    @Test
    void restoreOfNullSnapshotClears() {
        TenantContext.set("t", "u", "ADMIN");
        TenantContext.restore(null);
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
        assertNull(TenantContext.getRole());
    }

    /** 嵌套边界（请求线程 → 图线程 → 子任务）必须逐层还原，不能跳层。 */
    @Test
    void nestedCaptureRestoreUnwindsLayerByLayer() {
        TenantContext.set("outer", "outerUser", "USER");
        TenantContext.Snapshot outer = TenantContext.capture();

        TenantContext.set("inner", "innerUser", "ADMIN");
        TenantContext.Snapshot inner = TenantContext.capture();

        TenantContext.set("graph", "graphUser", "USER");

        TenantContext.restore(inner);
        assertEquals("inner", TenantContext.getTenantId());
        assertEquals("innerUser", TenantContext.getUserId());

        TenantContext.restore(outer);
        assertEquals("outer", TenantContext.getTenantId());
        assertEquals("USER", TenantContext.getRole());

        TenantContext.clear();
    }

    /** 只要有一个维度非空就不是空快照——否则 restore 会静默丢掉这个维度。 */
    @Test
    void snapshotIsEmptyOnlyWhenEveryDimensionIsNull() {
        assertTrue(new TenantContext.Snapshot(null, null, null).isEmpty());
        assertFalse(new TenantContext.Snapshot("t", null, null).isEmpty());
        assertFalse(new TenantContext.Snapshot(null, "u", null).isEmpty());
        assertFalse(new TenantContext.Snapshot(null, null, "USER").isEmpty());
    }

    /**
     * <b>本类存在的理由的一半</b>：线程池换线程后 ThreadLocal 不会跟过去，
     * 读它会<b>静默拿到 null</b>（不抛异常）——这正是需要用 capture/restore 显式搬运的原因。
     */
    @Test
    void threadLocalIsSilentlyNullOnAnotherThreadButSnapshotTravels() throws InterruptedException {
        TenantContext.set("t-cross", "u-cross", "USER");
        TenantContext.Snapshot snapshot = TenantContext.capture();

        java.util.concurrent.atomic.AtomicReference<String> before = new java.util.concurrent.atomic.AtomicReference<>("未执行");
        java.util.concurrent.atomic.AtomicReference<String> after = new java.util.concurrent.atomic.AtomicReference<>("未执行");

        Thread worker = new Thread(() -> {
            before.set(TenantContext.getTenantId());      // 不显式传递：null（静默）
            TenantContext.restore(snapshot);              // 显式传递：拿回提交线程的身份
            after.set(TenantContext.getTenantId());
            TenantContext.clear();                       // 池化线程必须自己收尾
        });
        worker.start();
        worker.join();

        assertNull(before.get(), "跨线程读 ThreadLocal 应当是 null —— 这就是必须有 capture/restore 的原因");
        assertEquals("t-cross", after.get());
        TenantContext.clear();
    }
}
