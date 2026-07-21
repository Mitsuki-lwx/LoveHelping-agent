package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.KnowledgeVote;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeVoteMapper extends BaseMapper<KnowledgeVote> {

    @Select("SELECT * FROM knowledge_vote WHERE session_id = #{sessionId} ORDER BY message_index ASC")
    List<KnowledgeVote> findBySessionId(@Param("sessionId") String sessionId);
}
