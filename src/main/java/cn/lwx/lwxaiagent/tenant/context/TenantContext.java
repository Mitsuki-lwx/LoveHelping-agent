package cn.lwx.lwxaiagent.tenant.context;

public class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    public static void set(String tenantId, String userId, String role) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        ROLE.set(role);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static String getRole() {
        return ROLE.get();
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        ROLE.remove();
    }
}
