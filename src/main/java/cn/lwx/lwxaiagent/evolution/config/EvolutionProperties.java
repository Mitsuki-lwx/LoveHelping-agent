package cn.lwx.lwxaiagent.evolution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <h1>进化系统配置属性 —— 控制 AI 自我进化的行为参数</h1>
 *
 * <p><strong>核心作用：</strong>作为 Spring Boot 的配置属性类，将 {@code application.yml}
 * 中以 {@code evolution} 为前缀的配置项自动绑定到此类的对应字段上，实现配置的外部化和动态调整。</p>
 *
 * <h2>配置前缀</h2>
 * <p>所有属性在 YAML 中的前缀为 <b>{@code evolution}</b>，例如：</p>
 * <pre>
 * evolution:
 *   enabled: true
 *   idle-timeout-seconds: 7200
 *   quality-threshold: 5
 *   extract-delay-seconds: 1800
 *   skill-top-k: 3
 *   skill-min-score: 0.5
 * </pre>
 *
 * <h2>各属性在进化流程中的作用</h2>
 * <pre>
 *                   ┌──────────────────────┐
 *                   │   ReflectionScheduler │  ← 使用 idleTimeoutSeconds + extractDelaySeconds
 *                   └──────────┬───────────┘    判断哪些会话需要反思
 *                              │
 *                   ┌──────────▼───────────┐
 *                   │    SkillReflector     │  ← 使用 qualityThreshold
 *                   └──────────┬───────────┘    过滤低质量技能
 *                              │
 *                   ┌──────────▼───────────┐
 *                   │    SkillRetriever     │  ← 使用 skillTopK + skillMinScore
 *                   └──────────────────────┘    控制检索数量和精度
 * </pre>
 *
 * <p>使用 Lombok {@code @Data} 注解自动生成所有字段的 getter 和 setter 方法。</p>
 *
 * @see cn.lwx.lwxaiagent.evolution.ReflectionScheduler 反思调度器
 * @see cn.lwx.lwxaiagent.evolution.SkillReflector 技能反思器
 * @see cn.lwx.lwxaiagent.evolution.SkillRetriever 技能检索器
 */
@Data
@ConfigurationProperties(prefix = "evolution")
public class EvolutionProperties {

    /**
     * <h3>是否启用 AI 自我进化机制</h3>
     *
     * <p>控制整个进化系统的总开关。当设置为 {@code false} 时：</p>
     * <ul>
     *   <li>{@code ReflectionScheduler} 跳过反思扫描（不再调用 LLM 分析对话）</li>
     *   <li>{@code SkillRetriever} 不检索技能（不在提示词中注入"已学经验"）</li>
     * </ul>
     * <p>默认值：<b>true</b>（启用）</p>
     */
    private boolean enabled = true;

    /**
     * <h3>会话总时长超限阈值（秒）</h3>
     *
     * <p>当会话的总持续时间（最后一条消息时间 - 第一条消息时间）超过此值时，
     * 无论用户是否仍在活跃发送消息，都会强制触发反思。</p>
     *
     * <p><strong>设计目的：</strong>这是一个安全兜底机制。考虑以下场景：</p>
     * <ul>
     *   <li>用户持续对话 3 小时，每次都间隔 10 分钟发送消息</li>
     *   <li>如果只看"最后一条消息距今"的空闲时间，这个会话永远不会触发反思</li>
     *   <li>但 3 小时的对话中可能包含大量有价值的经验</li>
     *   <li>通过总时长阈值，可以确保长时间会话也能被及时处理</li>
     * </ul>
     * <p>默认值：<b>7200</b>（2 小时）</p>
     */
    private int idleTimeoutSeconds = 7200;

    /**
     * <h3>技能质量评分最低阈值</h3>
     *
     * <p>LLM 在反思对话时会为每个提取的技能打出 1-10 分的质量评分。
     * 只有评分 <b>大于等于</b> 此阈值的技能才会被保存到数据库和向量存储中。</p>
     *
     * <p><strong>评分参考标准：</strong></p>
     * <ul>
     *   <li><b>10-8 分：</b>完整且适用范围广泛，可直接复用</li>
     *   <li><b>7-5 分：</b>有参考价值，需要根据上下文调整</li>
     *   <li><b>4-1 分：</b>模糊、空洞、无实际帮助 —— 被过滤掉</li>
     * </ul>
     * <p>默认值：<b>5</b></p>
     */
    private int qualityThreshold = 5;

    /**
     * <h3>最后一条消息后的等待延迟（秒）</h3>
     *
     * <p>当会话最后一条消息的时间距今超过此秒数时，认为会话已"冷静"，
     * 可以触发反思提取。这是触发反思的<b>主要条件</b>。</p>
     *
     * <p><strong>设置考虑：</strong></p>
     * <ul>
     *   <li>设置太短（如 5 分钟）→ 用户可能只是暂时离开，回来继续对话时会发现已有反思记录</li>
     *   <li>设置太长（如 24 小时）→ 有价值的经验不能被及时提取和利用</li>
     *   <li>当前默认 30 分钟是一个合理的平衡点</li>
     * </ul>
     * <p>默认值：<b>1800</b>（30 分钟）</p>
     */
    private int extractDelaySeconds = 1800;

    /**
     * <h3>检索时返回的 Top-K 技能数量</h3>
     *
     * <p>在用户发起新对话时，从向量存储中检索与当前问题最相关的历史技能，
     * 此值控制最终注入到提示词中的技能数量。</p>
     *
     * <p><strong>注意：</strong>由于向量存储中混有技能文档和普通知识库文档，
     * 实际搜索时会放大 5 倍（{@code skillTopK * 5}），从放大后的候选集中筛选出真正的技能文档。</p>
     * <p>默认值：<b>3</b></p>
     */
    private int skillTopK = 3;

    /**
     * <h3>RAG 检索的最低相似度分数</h3>
     *
     * <p>向量检索返回的文档相似度必须大于等于此值才会被保留。
     * 取值范围 0.0 ~ 1.0，值越高表示对匹配精度要求越严格。</p>
     *
     * <p><strong>设置考虑：</strong></p>
     * <ul>
     *   <li>设置太低（如 0.2）→ 可能返回不相关的技能，干扰 LLM 判断</li>
     *   <li>设置太高（如 0.9）→ 可能错过语义相关但表述不同的有用技能</li>
     * </ul>
     * <p>默认值：<b>0.5</b></p>
     */
    private double skillMinScore = 0.5;
}
