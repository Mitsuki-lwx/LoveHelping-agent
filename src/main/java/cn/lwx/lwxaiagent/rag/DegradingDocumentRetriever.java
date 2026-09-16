package cn.lwx.lwxaiagent.rag;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;

/**
 * <h1>检索降级装饰器 —— 检索不可用时"无上下文作答"，而不是让整个请求失败（ADR-30）</h1>
 *
 * <h2>为什么需要它</h2>
 * <p>2026-09-16 实测：embedding 上游故障（429 / 网络中断）时，{@link ParentChildDocumentRetriever}
 * 会把异常抛给 {@code RetrievalAugmentationAdvisor}，后者在 advisor 阶段直接终止流，
 * 用户侧看到 5000「AI 服务暂时不可用」。此时**整条普通聊天 / 三牌链路全挂**，
 * 而知识库检索只是"锦上添花"的增强项——不该具备把对话打死的能力。</p>
 *
 * <h2>为什么装饰在 advisor 边界，而不是改检索器本身</h2>
 * <ul>
 *   <li>ADR-26 明确要求 {@code ParentChildDocumentRetriever} 的观察者 span 在异常路径上
 *       "以 {@code rag.outcome=degraded} 标注后原样抛出，不吞异常、不替代返回"——
 *       该语义**保持不变**，本类在更外层接管。</li>
 *   <li>工具路径（{@code KnowledgeSearchTool}）依赖检索器抛错来表达"这次检索失败了"，
 *       由工具循环自行处理；只有 **prompt 装配路径**才需要降级为"无上下文作答"。</li>
 *   <li>与既有降级姿势对齐：{@code MemoryVectorStore.searchMemory}、
 *       {@code SkillRetriever.search}、rerank fallback 都是"失败即跳过、不影响对话"。</li>
 * </ul>
 *
 * <h2>代价</h2>
 * <p>检索故障时模型会在没有知识库依据的情况下作答（系统提示词与护栏仍然生效）。
 * 为免静默劣化，降级一律：① WARN 日志带原始异常信息 ② 计入 {@code rag.retrieve.degraded} 指标
 * ③ 内层 span 仍标 {@code rag.outcome=degraded}，可在 Langfuse 按 trace 归因。</p>
 */
@Slf4j
public class DegradingDocumentRetriever implements DocumentRetriever {

    /** 降级计数（08 §2.2 可观测契约）：检索失败被吞掉的次数，用于区分"没命中"与"检索挂了"。 */
    public static final String DEGRADED_METRIC = "rag.retrieve.degraded";

    private final DocumentRetriever delegate;
    private final MeterRegistry meterRegistry;

    public DegradingDocumentRetriever(DocumentRetriever delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<Document> retrieve(Query query) {
        try {
            return delegate.retrieve(query);
        } catch (Exception e) {
            // 降级路径上的任何二次异常（含指标上报）都不得再逃逸，否则降级失效
            log.warn("RAG retrieval failed, answering without retrieved context: {}", e.getMessage());
            try {
                meterRegistry.counter(DEGRADED_METRIC).increment();
            } catch (Exception ignored) {}
            return List.of();
        }
    }
}
