package cn.lwx.lwxaiagent.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级角色要求（2026-09-07 课3 RBAC 第 2 级——后端真校验）。
 * <p>标注于端点方法：要求当前登录用户角色等于 {@link #value()}，否则 403。
 * 由 {@code TenantInterceptor} 读取执行（与现有 JWT/租户上下文同链路，未引入 Spring Security）。
 *
 * <pre>
 * &#64;RequireRole("ADMIN")
 * &#64;DeleteMapping("/users/{id}")
 * public ... deleteUser(...) { ... }
 * </pre>
 *
 * 使用前提：端点在拦截器 addPathPatterns 白名单内（TenantInterceptor 会执行角色校验）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 所需角色：USER / ADMIN */
    String value();
}
