package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.service.EvolutionService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <h1>进化/投票控制器</h1>
 * <p>
 * 负责处理与 AI 模型"进化"（Evolution）相关的投票操作。
 * 在 AI 模型自我进化或参数优化的场景中，用户可以通过投票反馈
 * 来引导模型的进化方向，选择更优的模型变体或回复策略。
 * </p>
 *
 * <h2>URL 前缀</h2>
 * <p>所有接口路径以 "/evolution" 开头，对应类级别 @RequestMapping("/evolution")。</p>
 *
 * <h2>进化机制说明</h2>
 * <p>
 * "进化"（Evolution）是一种通过用户反馈来优化 AI 模型输出的机制：
 * </p>
 * <ol>
 *   <li>AI 模型可能生成多个不同的回复变体（variants）</li>
 *   <li>用户通过投票选择更好的回复</li>
 *   <li>系统收集投票数据，用于评估和优化模型参数</li>
 *   <li>经过多轮投票和迭代，模型逐渐"进化"出更优的回复策略</li>
 * </ol>
 *
 * <h2>依赖服务</h2>
 * <ul>
 *   <li>{@link EvolutionService}：进化服务，处理投票逻辑和模型进化算法</li>
 *   <li>{@link TenantContext}：租户上下文，获取当前租户 ID 用于多租户隔离</li>
 * </ul>
 *
 * @author lwx
 * @version 1.0
 * @see EvolutionService
 */
@Slf4j
@RestController
@RequestMapping("/evolution")
public class VoteController {

    /**
     * 进化服务
     * <p>通过构造函数注入，负责处理投票数据的存储和进化算法逻辑。
     * 内部使用 {@link EvolutionService.VoteRequest} 作为投票的数据模型。</p>
     */
    private final EvolutionService evolutionService;

    /**
     * 构造函数注入 EvolutionService
     *
     * @param evolutionService 进化服务实例，由 Spring 容器自动注入
     */
    public VoteController(EvolutionService evolutionService) {
        this.evolutionService = evolutionService;
    }

    /**
     * <h3>提交投票/反馈接口</h3>
     * <p>
     * 用户对 AI 的某个回复或模型变体进行投票，表达对该回复质量的评价。
     * 投票数据用于 AI 模型的进化优化，帮助系统学习用户的偏好。
     * </p>
     *
     * <p><b>HTTP 方法：</b>POST</p>
     * <p><b>请求路径：</b>/evolution/vote</p>
     * <p><b>Content-Type：</b>application/json</p>
     *
     * <p><b>请求体：</b>{@link EvolutionService.VoteRequest} 对象（需通过 @Valid 校验），
     * 具体字段取决于 EvolutionService 中 VoteRequest 的定义。</p>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * // 投票成功
     * {"code": 200, "data": "ok", "message": "success"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>A/B 测试：用户比较两个 AI 回复，选择更好的一个</li>
     *   <li>质量评分：用户对单个回复进行赞/踩评价</li>
     *   <li>模型进化：收集大量用户反馈数据，用于强化学习或参数优化</li>
     *   <li>偏好学习：系统根据用户投票学习个人偏好，提供个性化回复</li>
     * </ul>
     *
     * <p><b>注意事项：</b></p>
     * <ul>
     *   <li>请求体通过 @Valid 注解进行 JSR-303 参数校验，确保数据完整性</li>
     *   <li>租户 ID 从 TenantContext 自动获取，不需要在请求体中传递</li>
     * </ul>
     *
     * @param req 投票请求对象，包含投票相关的所有信息（如对话 ID、被投票的回复 ID、投票类型等），
     *            由 Spring 自动从请求体反序列化并通过 @Valid 进行参数校验
     * @return {@link Result}&lt;{@link String}&gt; 操作结果，成功时 data 为 "ok"
     */
    @PostMapping("/vote")
    public Result<String> vote(@Valid @RequestBody EvolutionService.VoteRequest req) {
        // 从租户上下文中获取当前租户 ID，与投票数据一起传递给进化服务
        evolutionService.vote(TenantContext.getTenantId(), req);
        return Result.ok("ok");
    }
}
