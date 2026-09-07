package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 审计日志 Mapper（V20）。审计表 append-only——业务代码不得调用其 update/delete。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
