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
}
