package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.GuardrailRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 护栏规则 Mapper（ADR-6）。
 */
@Mapper
public interface GuardrailRuleMapper extends BaseMapper<GuardrailRule> {
}
