package cn.lwx.lwxaiagent.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计埋点注解（V20，2026-09-07 课3）。
 * <p>标注在敏感端点方法上（登录/改密/注销/删数据/管理操作），由 {@link AuditAspect} 切面
 * 自动记录：谁(登录用户)·action·resource(HTTP URI)·结果(SUCCESS/FAIL——返回 Map success=false
 * 视为业务失败)。
 *
 * <pre>
 * &#64;AuditLog("change_password")
 * &#64;PutMapping("/password")
 * public Map&lt;String, Object&gt; changePassword(...) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 审计动作名（小写下划线风格：login / delete_account / sandbox_delete） */
    String value();
}
