package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.AuditLog;
import cn.lwx.lwxaiagent.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计日志服务（V20，2026-09-07 课3）。
 * <p>审计 = 事后取证，与业务日志分离：独立 append-only 表，记录"谁·何时·对什么·做了什么·结果"。
 * 通过 {@link cn.lwx.lwxaiagent.audit.AuditLog} 注解 + {@link cn.lwx.lwxaiagent.audit.AuditAspect}
 * 自动切面写入；管理端经 /admin/audit 查询。
 */
@Slf4j
@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 记录一条审计。写失败不阻断业务（审计是附加职责，日志降级为 error 即可）。
     */
    public void record(String actor, String action, String resource, String result,
                       String detail, String ip) {
        try {
            AuditLog entry = new AuditLog();
            entry.setActor(actor == null || actor.isBlank() ? "anonymous" : actor);
            entry.setAction(truncate(action, 50));
            entry.setResource(truncate(resource, 200));
            entry.setResult(result);
            entry.setDetail(truncate(detail, 500));
            entry.setIp(ip);
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.error("audit write failed: action={} err={}", action, e.getMessage());
        }
    }

    /** 最近 N 条审计（管理端查询用，按时间倒序）。 */
    public List<AuditLog> recent(int limit) {
        int n = Math.min(Math.max(limit, 1), 500);
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + n));
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
