package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.MessageMedia;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图片附件 Mapper（ADR-11）。归属校验在 Service/Controller 层强制。
 */
@Mapper
public interface MessageMediaMapper extends BaseMapper<MessageMedia> {
}
