package cn.lwx.lwxaiagent.tenant;

/**
 * 租户上下文（ThreadLocal）。
 * <p>
 * 每个请求线程持有一份独立的租户信息，请求结束后由 TenantFilter 清理。
 * 业务代码通过 TenantContext.getTenantId() 获取当前租户 ID，
 * 用于检索过滤、数据隔离等场景。
 * <p>
 * 设计原则：
 * - ThreadLocal 保证线程隔离，不同请求不会互相干扰
 * - 请求结束必须 clear()，防止线程池复用导致数据泄漏
 * - 不做配额、不做审计、不做用户管理（升级计划明确不做）
 */
public class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    /** 设置当前请求的租户信息（由 TenantInterceptor 调用） */
    public static void set(String tenantId, String userId, String role) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        ROLE.set(role);
    }

    /** 获取当前租户 ID（业务代码调用，用于检索过滤） */
    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /** 获取当前用户 ID */
    public static String getUserId() {
        return USER_ID.get();
    }

    /** 获取当前用户角色 */
    public static String getRole() {
        return ROLE.get();
    }

    /** 清理 ThreadLocal（由 TenantFilter 在请求结束时调用） */
    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        ROLE.remove();
    }
}
