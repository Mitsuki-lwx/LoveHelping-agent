package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.AgentTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 任务 Mapper（ADR-3）。
 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTask> {
}
