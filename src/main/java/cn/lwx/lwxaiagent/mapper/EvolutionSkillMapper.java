package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

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
     *
     * <p>⛔ V35（2026-10-13）：audit_status 显式写 <b>NO_SKILL</b>，不再用默认的 PENDING——
     * 否则占位行会混进技能审核队列（实测占 36%），管理员翻页全是空行。</p>
     */
    @Insert("INSERT INTO evolution_skill (tenant_id, skill_name, description, content, source_session_id, quality_score, is_active, audit_status) "
            + "VALUES ('default', '__reflected_no_skill__', '', '', #{sessionId}, 0, 0, 'NO_SKILL')")
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

    /**
     * 按审核状态分页查询（V34 审核闭环）。
     *
     * <p>⛔ 必须带 {@code limit}：库里 PENDING 曾有 1.2 万+ 条，无条件全量查询会拖垮内存与响应。
     * 由 {@code VoteController} 把 limit 归一化到 [1,200]。</p>
     */
    /**
     * 按审核状态计数（V35）：口径与 {@link #findByAuditStatus} **一致**（排除占位行）。
     *
     * <p>⛔ 前端不许再写死积压数字：写死的那个数会随真实积压漂移，
     * 变成"界面说 12,532、实际 8,347"的**撒谎数字**（本仓最典型的复发缺陷）。</p>
     */
    @Select("SELECT COUNT(*) FROM evolution_skill WHERE audit_status = #{status} "
            + "AND skill_name <> '__reflected_no_skill__'")
    long countByAuditStatus(@Param("status") String status);

    @Select("SELECT * FROM evolution_skill WHERE audit_status = #{status} "
            + "AND skill_name <> '__reflected_no_skill__' "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<EvolutionSkill> findByAuditStatus(@Param("status") String status, @Param("limit") int limit);

    /**
     * 批量驳回候选 id（V34）：{@code audit_status='PENDING'} 且 {@code quality_score <= maxScore}。
     *
     * <p>只返回 id（不取整行），供 {@link #bulkRejectByIds} 分批更新——
     * 12k 行的大事务会锁表，所以先限量取 id、再按 ≤500 一批更新。</p>
     */
    @Select("SELECT id FROM evolution_skill WHERE audit_status = 'PENDING' AND quality_score <= #{maxScore} "
            + "ORDER BY id LIMIT #{limit}")
    List<Long> findPendingIdsByMaxScore(@Param("maxScore") int maxScore, @Param("limit") int limit);

    /**
     * 按 id 批量置为 REJECTED / is_active=0。
     *
     * <p>⛔ WHERE 里再带一次 {@code audit_status='PENDING'}：<b>已 APPROVED 的技能绝不能被驳回</b>，
     * 即使调用方误传了它的 id（纵深防御，不靠调用方自觉）。</p>
     */
    @Update("<script>UPDATE evolution_skill SET audit_status = 'REJECTED', is_active = 0 "
            + "WHERE audit_status = 'PENDING' AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int bulkRejectByIds(@Param("ids") List<Long> ids);
}
