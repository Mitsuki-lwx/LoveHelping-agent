package cn.lwx.lwxaiagent.evolution.config;

import cn.lwx.lwxaiagent.evolution.SkillReflector;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
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
     * </ul>
     *
     * @return 进化系统专用的线程池执行器，Bean 名称为 "evolutionExecutor"
     */
    @Bean(name = "evolutionExecutor")
    public Executor evolutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("evolution-");
        executor.setDaemon(true);
        return executor;
    }

    /**
     * <h3>创建技能反思器 Bean</h3>
     *
     * <p>实例化 {@link SkillReflector}，注入以下依赖：</p>
     * <ul>
     *   <li><b>chatModel：</b>通过 {@code @Qualifier("deepSeekChatModel")} 指定使用 DeepSeek 聊天模型，
     *       因为反思操作需要较强的推理能力，DeepSeek 适合此类复杂分析任务</li>
     *   <li><b>qualityThreshold：</b>从 {@link EvolutionProperties#getQualityThreshold()} 获取质量阈值，
     *       只有评分达到此阈值的技能才会被保存</li>
     * </ul>
     *
     * @param chatModel DeepSeek 聊天模型实例（由 Spring AI 自动配置 + @Qualifier 指定）
     * @param props     进化系统配置属性，提供质量阈值等参数
     * @return 配置完成的 SkillReflector 实例
     */
    @Bean
    public SkillReflector skillReflector(
            @Qualifier("deepSeekChatModel") ChatModel chatModel,
            EvolutionProperties props) {
        return new SkillReflector(chatModel, props.getQualityThreshold());
    }
}
