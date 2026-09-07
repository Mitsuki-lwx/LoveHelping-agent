package cn.lwx.lwxaiagent.audit;

import cn.lwx.lwxaiagent.service.AuditService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * 审计切面（V20，2026-09-07 课3）。
 * <p>{@link AuditLog} 注解方法的统一审计入口：
 * <ul>
 *   <li>正常返回 → SUCCESS；但返回体是 {@code Map} 且 {@code success=false}（本项目业务失败
 *       的 Map 契约，如登录密码错误）→ 记为 FAIL（detail 带 message）</li>
 *   <li>抛异常 → FAIL，记录后原样抛出（不改变错误传播）</li>
 *   <li>actor 取登录用户；匿名取 anonymous；ip 取请求来源</li>
 * </ul>
 * 审计写入自身失败不影响业务（AuditService.record 已吞）。
 */
@Slf4j
@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            record(auditLog.value(), evaluateResult(result), resultDetail(result));
            return result;
        } catch (Throwable t) {
            record(auditLog.value(), "FAIL", t.getMessage());
            throw t;
        } finally {
            long ms = System.currentTimeMillis() - start;
            if (ms > 2000) {
                log.warn("audit slow action={} {}ms", auditLog.value(), ms);
            }
        }
    }

    /** Map 成功契约识别：{success:false} = 业务失败（如登录密码错） */
    private String evaluateResult(Object result) {
        if (result instanceof Map<?, ?> m) {
            Object success = m.get("success");
            if (Boolean.FALSE.equals(success)) return "FAIL";
        }
        return "SUCCESS";
    }

    private String resultDetail(Object result) {
        if (result instanceof Map<?, ?> m) {
            Object msg = m.get("message");
            if (msg instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private void record(String action, String result, String detail) {
        String uri = null;
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            uri = req.getRequestURI();
            ip = req.getRemoteAddr();
        }
        auditService.record(TenantContext.getUserId(), action, uri, result, detail, ip);
    }
}
