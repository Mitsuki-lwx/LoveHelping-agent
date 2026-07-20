package cn.lwx.lwxaiagent.evolution;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeEntryMapper extends BaseMapper<KnowledgeEntry> {

    @Select("SELECT COUNT(*) FROM knowledge_entry WHERE source_session_id = #{sessionId}")
    int countBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM knowledge_entry WHERE entry_type IN ('PROCESS','PATTERN','PRINCIPLE') AND is_active = 1")
    List<KnowledgeEntry> findActiveExperiences();

    @Select("SELECT * FROM knowledge_entry WHERE entry_type = 'CASE' AND label = #{label} AND is_active = 1")
    List<KnowledgeEntry> findActiveCasesByLabel(@Param("label") String label);
}
