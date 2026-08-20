package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.UserMemory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户事实记忆 Mapper（ADR-14）。归属过滤在 Service/Controller 层强制校验。
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {
}
