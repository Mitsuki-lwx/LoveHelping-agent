package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.ConversationSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话摘要 Mapper（ADR-14）。
 */
@Mapper
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummary> {
}
