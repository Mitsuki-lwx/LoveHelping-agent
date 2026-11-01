package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * <h1>归属校验收口</h1>
 *
 * <p>按 id 读取用户资源的路径统一走这里，而不是各 service 自己写
 * {@code if (!userId.equals(x.getUserId()))}。手写比对的问题不是"会写错"，
 * 而是<b>漏写一次就重新开一个 IDOR，且没有任何信号</b>。</p>
 *
 * <p>配套的盘点测试 {@code OwnershipInventoryTest} 会枚举控制器里所有带
 * {@code {*Id}} 的端点，要求每个都在登记表里声明用哪条机制——新增端点漏登记即变红。</p>
 *
 * <p>change: {@code security-hardening}（2026-11-01）。</p>
 */
@Component
public class Ownership {

    /**
     * 严格版：未登录 → 401；资源不存在 → 404；非本人 → 403。
     *
     * @param resource 已加载的资源（可为 null，表示不存在）
     * @param ownerOf  从资源取归属用户 id 的方法引用
     * @return 原资源，便于 {@code var x = ownership.require(load(id), Foo::getUserId);}
     */
    public <T> T require(T resource, Function<T, String> ownerOf) {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        if (resource == null) {
            throw new BizException(404, "资源不存在");
        }
        if (!userId.equals(ownerOf.apply(resource))) {
            throw new BizException(403, "无权访问该资源");
        }
        return resource;
    }

    /**
     * 宽松版：不抛异常，非本人或未登录时返回 {@code null}。
     * 保留给既有 {@code owned(...)} 语义（返回 null 表示"不是你的"，由调用方决定后续动作）。
     */
    public <T> T requireOrNull(T resource, Function<T, String> ownerOf) {
        return requireOrNull(TenantContext.getUserId(), resource, ownerOf);
    }

    /**
     * 宽松版（显式身份）：调用方自己持有 userId 时用这个重载——
     * service 层常把 userId 当参数传，且单测里未必有 TenantContext。
     */
    public <T> T requireOrNull(String userId, T resource, Function<T, String> ownerOf) {
        if (userId == null || resource == null) {
            return null;
        }
        return userId.equals(ownerOf.apply(resource)) ? resource : null;
    }
}
