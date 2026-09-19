package cn.lwx.lwxaiagent.evolution.config;

import cn.lwx.lwxaiagent.evolution.SkillReflector;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import cn.lwx.lwxaiagent.infrastructure.observability.TraceContextTaskDecorator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * <h1>进化系统配置类 —— 管理 AI 自我进化的基础设施</h1>
 *
 * <p><strong>核心作用：</strong>作为整个 AI 自我进化（Self-Evolution）系统的 Spring 配置入口，
 * 负责启用必要的 Spring 特性、配置专用的线程池、并创建核心 Bean 实例。</p>
 *
 * <h2>启用的 Spring 特性</h2>
 * <ul>
 *   <li><b>{@link EnableAsync}：</b>启用 Spring 的异步方法执行能力，使得 {@link SkillReflector#reflect}
 *       方法可以在专用线程池中异步执行，不阻塞主业务流程</li>
 *   <li><b>{@link EnableScheduling}：</b>启用 Spring 的定时任务调度能力，使得
 *       {@link cn.lwx.lwxaiagent.evolution.ReflectionScheduler#scanAndReflect} 方法可以按固定间隔自动执行</li>
 * </ul>
 *
 * <h2>线程池配置说明</h2>
 * <p>进化系统的反思操作（调用 LLM 分析对话、提取技能）是一个相对耗时的操作，
 * 因此使用独立的线程池进行隔离：</p>
 * <ul>
 *   <li><b>核心线程数 1：</b>正常情况只需 1 个线程处理反思任务</li>
 *   <li><b>最大线程数 2：</b>高峰期允许扩展到 2 个线程（防止队列积压过深）</li>
 *   <li><b>队列容量 100：</b>最多缓存 100 个待处理的反思任务</li>
 *   <li><b>守护线程模式：</b>设为守护线程，应用关闭时不会因为等待线程池而延迟退出</li>
 *   <li><b>线程名前缀 "evolution-"：</b>便于在日志和监控中区分进化系统的线程</li>
 * </ul>
 *
 * <h2>Bean 依赖关系</h2>
 * <pre>
 * EvolutionConfig
 *   ├── evolutionExecutor (Executor)        → 异步线程池
 *   └── skillReflector (SkillReflector)     → 核心反思组件
 *         ├── 依赖 ChatModel (DeepSeek)       → LLM 调用
 *         └── 依赖 EvolutionProperties        → 配置参数
 * </pre>
 *
 * @see SkillReflector 技能反思器
 * @see EvolutionProperties 进化系统配置属性
 * @see cn.lwx.lwxaiagent.evolution.ReflectionScheduler 反思调度器
 */
@Configuration
@EnableAsync
@EnableScheduling
public class EvolutionConfig {

    /**
     * <h3>创建进化系统专用线程池</h3>
     *
     * <p>配置一个用于执行反思操作的独立线程池。反思操作包括：</p>
     * <ul>
     *   <li>从数据库读取对话历史</li>
     *   <li>调用 LLM（DeepSeek）分析对话并提取技能</li>
     *   <li>将提取的技能写入 MySQL 和向量存储</li>
     * </ul>
     * <p>这些操作相对耗时且不需要同步返回结果，因此使用异步线程池执行是最佳实践。</p>
     *
     * <h4>线程池参数：</h4>
     * <ul>
     *   <li><b>核心线程数（corePoolSize）：</b>1 —— 正常负载下保持 1 个线程</li>
     *   <li><b>最大线程数（maxPoolSize）：</b>2 —— 高峰期可扩展到 2 个线程</li>
     *   <li><b>队列容量（queueCapacity）：</b>100 —— 最多积压 100 个待处理任务</li>
     *   <li><b>线程名前缀（threadNamePrefix）：</b>"evolution-" —— 日志中便于识别</li>
     *   <li><b>守护线程（daemon）：</b>true —— JVM 关闭时无需等待此线程池</li>
     *   <li><b>任务装饰器（TaskDecorator）：</b>把父 trace 上下文从提交线程带到执行线程
     *       （ADR-34）——否则 {@code @Async} 换线程后父上下文丢失，后台任务的
     *       {@code llm.attempt} 会自成新根 trace，平台上无法按 traceId 追溯归属</li>
     * </ul>
     *
     * @param telemetry 遥测门面，供装饰器在提交线程捕获父上下文
     * @return 进化系统专用的线程池执行器，Bean 名称为 "evolutionExecutor"
     */
    @Bean(name = "evolutionExecutor")
    public Executor evolutionExecutor(AiTelemetry telemetry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("evolution-");
        executor.setDaemon(true);
        // ADR-34：@Async 跨线程后父 trace 上下文会丢失，显式在此边界传播。
        executor.setTaskDecorator(new TraceContextTaskDecorator(telemetry, "task evolution.reflect"));
        return executor;
    }

    /**
     * <h3>创建技能反思器 Bean</h3>
     *
     * <p>实例化 {@link SkillReflector}，注入以下依赖：</p>
     * <ul>
     *   <li><b>chatModel：</b>注入容器里 {@code @Primary} 的 {@link ChatModel}——即 {@code LlmGateway}。
     *       ADR-23 规定它是<b>唯一的重试归属者与准入点</b>：反思调用同样要受并发许可、供应商熔断、
     *       重试预算约束，用量也需能被 {@code llm.usage.owner=gateway} 归因。此前这里用
     *       {@code @Qualifier("openAiChatModel")} 直连供应商模型，绕过了上述全部保护
     *       （ADR-31 发现三，2026-09-17 修正）。</li>
     *   <li><b>qualityThreshold：</b>从 {@link EvolutionProperties#getQualityThreshold()} 获取质量阈值，
     *       只有评分达到此阈值的技能才会被保存</li>
     * </ul>
     *
     * @param chatModel {@code @Primary} 聊天模型实例（即 {@code LlmGateway}，经唯一准入点）
     * @param props     进化系统配置属性，提供质量阈值等参数
     * @return 配置完成的 SkillReflector 实例
     */
    @Bean
    public SkillReflector skillReflector(ChatModel chatModel, EvolutionProperties props) {
        return new SkillReflector(chatModel, props.getQualityThreshold());
    }
}
