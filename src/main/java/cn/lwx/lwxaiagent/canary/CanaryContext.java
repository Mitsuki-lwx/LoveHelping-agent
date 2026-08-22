package cn.lwx.lwxaiagent.canary;

/**
 * 灰度上下文（ThreadLocal）：标记当前请求是否在灰度桶内。
 * 由 CanaryInterceptor 在 preHandle() 中设置，afterCompletion() 清理。
 */
public class CanaryContext {

    private static final ThreadLocal<Boolean> IS_CANARY = new ThreadLocal<>();

    public static void set(boolean canary) { IS_CANARY.set(canary); }
    public static boolean isCanary() { return Boolean.TRUE.equals(IS_CANARY.get()); }
    public static void clear() { IS_CANARY.remove(); }

    /**
     * 用户的灰度桶位（0-99），用于管理端点查询。
     */
    private static final ThreadLocal<Integer> BUCKET = new ThreadLocal<>();

    public static void setBucket(int bucket) { BUCKET.set(bucket); }
    public static int getBucket() { Integer b = BUCKET.get(); return b != null ? b : -1; }
    public static void clearBucket() { BUCKET.remove(); }
}
