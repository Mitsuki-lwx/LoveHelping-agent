package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;

import java.util.function.Function;

/**
 * <h1>归属校验收口（无状态静态工具）</h1>
 *
 * <p>按 id 读取用户资源的路径统一走这里，而不是各 service 自己写
 * {@code if (!userId.equals(x.getUserId()))}。手写比对的问题不是"会写错"，
 * 而是<b>漏写一次就重新开一个 IDOR，且没有任何信号</b>。</p>
 *
 * <p>做成静态工具是刻意的：它没有状态，注入它只会强迫每个调用方改构造签名、
 * 并让单测多一个 mock 依赖。纯函数就该是纯函数。</p>
 *
 * <p>配套的盘点测试 {@code OwnershipInventoryTest} 会枚举控制器里所有带
 * {@code {*Id}} 的端点，要求每个都在方法体里留下归属校验信号——新增端点漏写即变红。</p>
 *
 * <p>changes: {@code security-hardening}（引入）、{@code ownership-rollout}（全量迁移）。</p>
 */
public final class Ownership {

    private Ownership() {
    }

    /**
     * 严格版：未登录 → 401；资源不存在 → 404；非本人 → 403（用给定文案）。
     *
     * @param userId           当前用户
     * @param resource         已加载的资源（可为 null，表示不存在）
     * @param ownerOf          从资源取归属用户 id 的方法引用
     * @param forbiddenMessage 越权时的文案（调用方保持原样，避免行为漂移）
     */
    public static <T> T require(String userId, T resource, Function<T, String> ownerOf, String forbiddenMessage) {
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        if (resource == null) {
            throw new BizException(404, "资源不存在");
        }
        if (!userId.equals(ownerOf.apply(resource))) {
            throw new BizException(403, forbiddenMessage);
        }
        return resource;
    }

    /** 严格版（从 {@link TenantContext} 取当前用户）。 */
    public static <T> T require(T resource, Function<T, String> ownerOf, String forbiddenMessage) {
        return require(TenantContext.getUserId(), resource, ownerOf, forbiddenMessage);
    }

    /**
     * 宽松版：非本人或未登录时返回 {@code null}，不抛异常。
     * 保留给既有 {@code owned(...)} 语义（返回 null 表示"不是你的"，由调用方决定后续动作）。
     */
    public static <T> T requireOrNull(String userId, T resource, Function<T, String> ownerOf) {
        if (userId == null || resource == null) {
            return null;
        }
        return userId.equals(ownerOf.apply(resource)) ? resource : null;
    }

    /** 宽松版（从 {@link TenantContext} 取当前用户）。 */
    public static <T> T requireOrNull(T resource, Function<T, String> ownerOf) {
        return requireOrNull(TenantContext.getUserId(), resource, ownerOf);
    }
}
