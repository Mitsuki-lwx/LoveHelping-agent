package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EvolutionSkillMapper extends BaseMapper<EvolutionSkill> {

    @Select("SELECT COUNT(*) FROM evolution_skill WHERE source_session_id = #{sessionId}")
    int countBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM evolution_skill WHERE is_active = 1 AND tenant_id = #{tenantId} ORDER BY quality_score DESC")
    List<EvolutionSkill> findActiveByTenant(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM evolution_skill WHERE is_active = 1 AND tenant_id = #{tenantId} AND quality_score >= #{minScore}")
    List<EvolutionSkill> findActiveByTenantAndMinScore(@Param("tenantId") String tenantId,
                                                        @Param("minScore") int minScore);
}
