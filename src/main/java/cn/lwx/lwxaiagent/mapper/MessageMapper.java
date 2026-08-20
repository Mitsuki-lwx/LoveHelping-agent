package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息 Mapper（Phase 2 落库真源）。归属校验在 Service/Controller 层强制（防 IDOR）。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
