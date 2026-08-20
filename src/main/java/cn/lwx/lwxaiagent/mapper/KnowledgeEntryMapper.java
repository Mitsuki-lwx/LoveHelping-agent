package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.KnowledgeEntry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 知识条目 Mapper 接口（已废弃）—— 对应数据库表 <strong>knowledge_entry</strong>。
 * </p>
 *
 * <p>
 * <strong>注意：此接口已标记为 @Deprecated，被 {@link EvolutionSkillMapper} 取代。</strong>
 * 保留此接口仅用于读取旧版本遗留数据，<strong>新代码请使用 EvolutionSkillMapper</strong>。
 * 请勿在新功能中调用此接口的任何方法。
 * </p>
 *
 * <p>
 * <strong>废弃原因：</strong>KnowledgeEntry 是早期版本的知识管理数据模型，
 * 缺乏向量检索（Milvus + ES）集成，字段设计也不如 EvolutionSkill 规范。
 * 重构后的 EvolutionSkill 体系提供了更完善的知识管理和语义检索能力。
 * </p>
 *
 * <p>
 * <strong>继承关系：</strong>继承 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper}，
 * 自动获得了通用的 CRUD 方法（但由于接口已废弃，不建议继续使用）。
 * </p>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.entity.KnowledgeEntry
 * @see cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper
 * @deprecated 请使用 {@link cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper} 替代
 */
@Mapper
@Deprecated
public interface KnowledgeEntryMapper extends BaseMapper<KnowledgeEntry> {

    /**
     * 统计指定会话产生的知识条目数量。
     * <p>用于判断某个对话会话是否已经提取过知识条目，与 {@link EvolutionSkillMapper#countBySessionId(String)}
     * 功能类似，但查询的是旧版 {@code knowledge_entry} 表。</p>
     *
     * <p><strong>SQL 逻辑：</strong>{@code SELECT COUNT(*) FROM knowledge_entry WHERE source_session_id = ?}</p>
     *
     * @param sessionId 会话 ID（{@code source_session_id}），标识某次对话会话
     * @return 该会话已产生的知识条目总数（int 类型）
     * @deprecated 新代码请使用 {@link EvolutionSkillMapper#countBySessionId(String)}
     */
    @Select("SELECT COUNT(*) FROM knowledge_entry WHERE source_session_id = #{sessionId}")
    int countBySessionId(@Param("sessionId") String sessionId);

    /**
     * 查询全部活跃的经验类知识条目（PROCESS、PATTERN、PRINCIPLE 类型）。
     * <p>经验类条目包括流程经验、模式总结和原则提炼三种类型，
     * 此方法排除 CASE（案例）类型，只返回通用可复用的经验知识。
     * 结果用于在对话中注入通用经验指导。</p>
     *
     * <p><strong>SQL 逻辑：</strong>
     * {@code SELECT * FROM knowledge_entry WHERE entry_type IN ('PROCESS','PATTERN','PRINCIPLE') AND is_active = 1}</p>
     * <ul>
     *   <li>{@code entry_type IN (...)} —— 限定条目类型为 PROCESS、PATTERN、PRINCIPLE 三种经验类型</li>
     *   <li>{@code is_active = 1} —— 只返回活跃状态的条目</li>
     * </ul>
     *
     * @return 全部活跃的经验类知识条目列表
     * @deprecated 新代码请使用 {@link EvolutionSkillMapper#findActiveByTenant(String)}
     */
    @Select("SELECT * FROM knowledge_entry WHERE entry_type IN ('PROCESS','PATTERN','PRINCIPLE') AND is_active = 1")
    List<KnowledgeEntry> findActiveExperiences();

    /**
     * 按标签（label）查询活跃的案例类知识条目（CASE 类型）。
     * <p>用于根据业务标签检索相关的历史案例。例如，当用户提出"支付失败"问题时，
     * 可以用 label = "支付问题" 来检索之前记录的支付相关案例。</p>
     *
     * <p><strong>SQL 逻辑：</strong>
     * {@code SELECT * FROM knowledge_entry WHERE entry_type = 'CASE' AND label = ? AND is_active = 1}</p>
     * <ul>
     *   <li>{@code entry_type = 'CASE'} —— 限定只查询案例类型的条目</li>
     *   <li>{@code label = ?} —— 按标签精确匹配，实现按业务领域筛选</li>
     *   <li>{@code is_active = 1} —— 只返回活跃状态的条目</li>
     * </ul>
     *
     * @param label 分类标签，用于按业务领域筛选案例（如"支付问题"、"登录异常"等）
     * @return 指定标签下的活跃案例条目列表
     * @deprecated 新代码请使用 {@link EvolutionSkillMapper#findActiveByTenantAndMinScore(String, int)}
     */
    @Select("SELECT * FROM knowledge_entry WHERE entry_type = 'CASE' AND label = #{label} AND is_active = 1")
    List<KnowledgeEntry> findActiveCasesByLabel(@Param("label") String label);
}
