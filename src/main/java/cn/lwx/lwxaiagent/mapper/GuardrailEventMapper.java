package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.GuardrailEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 护栏事件 Mapper（ADR-6）。
 */
@Mapper
public interface GuardrailEventMapper extends BaseMapper<GuardrailEvent> {
}
