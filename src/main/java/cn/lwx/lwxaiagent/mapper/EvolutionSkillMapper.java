package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.util.List;

/**
 * <p>
 * 进化技能 Mapper 接口 —— 对应数据库表 <strong>evolution_skill</strong>。
 * </p>
 *
 * <p>
 * <strong>核心作用：</strong>该接口是 MyBatis 的数据访问层（DAO），负责 {@link EvolutionSkill} 实体
 * 与数据库表 {@code evolution_skill} 之间的映射和 SQL 操作。
 * </p>
 *
 * <p>
 * <strong>继承关系：</strong>继承 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper}，
 * 自动获得了通用的 CRUD（增删改查）方法，包括：
 * </p>
 * <ul>
 *   <li>{@code insert(T entity)} —— 插入一条记录</li>
 *   <li>{@code deleteById(Serializable id)} —— 根据 ID 删除记录</li>
 *   <li>{@code updateById(T entity)} —— 根据 ID 更新记录</li>
 *   <li>{@code selectById(Serializable id)} —— 根据 ID 查询记录</li>
 *   <li>{@code selectList(Wrapper<T> wrapper)} —— 条件查询列表</li>
 *   <li>以及其他 MyBatis-Plus 提供的 CRUD 方法</li>
 * </ul>
 *
 * <p>
 * <strong>自定义 SQL：</strong>本接口定义的自定义方法使用 {@link org.apache.ibatis.annotations.Select @Select}
 * 注解直接编写 SQL，属于 MyBatis 注解式开发方式，无需 XML 映射文件。
 * </p>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.entity.EvolutionSkill
 */
@Mapper
public interface EvolutionSkillMapper extends BaseMapper<EvolutionSkill> {

    /**
     * 统计指定会话产生的进化技能数量。
     * <p>用于判断某个对话会话是否已经提取过技能，避免重复提取。
     * 在对话反思阶段，系统会先调用此方法检查该会话是否已经处理过。</p>
     *
     * <p><strong>SQL 逻辑：</strong>执行 {@code SELECT COUNT(*) FROM evolution_skill WHERE source_session_id = ?}，
     * 统计 {@code source_session_id} 等于指定会话 ID 的全部记录数。</p>
     *
     * @param sessionId 会话 ID（{@code source_session_id}），标识某次对话会话
     * @return 该会话已产生的进化技能总数（int 类型）
     */
    @Select("SELECT COUNT(*) FROM evolution_skill WHERE source_session_id = #{sessionId}")
    int countBySessionId(@Param("sessionId") String sessionId);

    /**
     * 零产出占位（2026-09-05 中危修复）：反思无有效技能时插 is_active=0 占位行——
     * 让 {@code source_session_id} 去重生效，避免该会话永久重候选、每 5min 重复烧 LLM。
     * 检索方均过滤 is_active=1，占位行不参与任何技能消费。
     */
    @Insert("INSERT INTO evolution_skill (tenant_id, skill_name, description, content, source_session_id, quality_score, is_active) "
            + "VALUES ('default', '__reflected_no_skill__', '', '', #{sessionId}, 0, 0)")
    int insertSkipMark(@Param("sessionId") String sessionId);

    /**
     * 查询指定租户下全部活跃的进化技能，按质量评分降序排列。
     * <p>用于在新对话开始时，为当前租户加载所有可用的进化技能，
     * 返回的技能列表按质量评分从高到低排序，确保高质量技能优先被检索和使用。</p>
     *
     * <p><strong>SQL 逻辑：</strong>
     * {@code SELECT * FROM evolution_skill WHERE is_active = 1 AND tenant_id = ? ORDER BY quality_score DESC}</p>
     * <ul>
     *   <li>{@code is_active = 1} —— 只返回活跃状态的技能，过滤掉已停用的</li>
     *   <li>{@code tenant_id = ?} —— 多租户隔离，只查询当前租户的数据</li>
     *   <li>{@code ORDER BY quality_score DESC} —— 按质量评分降序，高分在前</li>
     * </ul>
     *
     * @param tenantId 租户 ID，用于多租户数据隔离
     * @return 该租户下全部活跃的进化技能列表，按质量评分从高到低排序
     */
    @Select("SELECT * FROM evolution_skill WHERE is_active = 1 AND tenant_id = #{tenantId} ORDER BY quality_score DESC")
    List<EvolutionSkill> findActiveByTenant(@Param("tenantId") String tenantId);

    /**
     * 查询指定租户下质量评分不低于最低阈值的活跃进化技能。
     * <p>在 {@link #findActiveByTenant(String)} 的基础上增加了最低评分过滤条件，
     * 用于只检索高质量技能的场景（例如，仅将评分高于某个阈值的技能注入 prompt）。</p>
     *
     * <p><strong>SQL 逻辑：</strong>
     * {@code SELECT * FROM evolution_skill WHERE is_active = 1 AND tenant_id = ? AND quality_score >= ?}</p>
     * <ul>
     *   <li>{@code quality_score >= ?} —— 额外的质量评分过滤条件，只返回评分不低于 {@code minScore} 的技能</li>
     * </ul>
     *
     * @param tenantId 租户 ID，用于多租户数据隔离
     * @param minScore 最低质量评分阈值，只返回评分大于等于此值的技能
     * @return 满足质量评分要求的活跃技能列表
     */
    @Select("SELECT * FROM evolution_skill WHERE is_active = 1 AND tenant_id = #{tenantId} AND quality_score >= #{minScore}")
    List<EvolutionSkill> findActiveByTenantAndMinScore(@Param("tenantId") String tenantId,
                                                        @Param("minScore") int minScore);
}
