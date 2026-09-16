package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * <h1>SkillRetriever 降级契约测试</h1>
 *
 * <p>本类的契约是「技能注入是增强项，向量 / embedding 不可用时必须跳过、不影响对话」。
 * 2026-09-16 实测发现该契约曾被违反：embedding 上游故障（429 / 网络中断）时
 * {@code similaritySearch} 直接抛出，把整条聊天链路带崩为 5000「AI 服务暂时不可用」，
 * 使真实 E2E 22 项中 7 项失败。此处把该行为固化为回归测试。</p>
 */
@ExtendWith(MockitoExtension.class)
class SkillRetrieverTest {

    @Mock
    private EvolutionProperties props;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private Counter degradedCounter;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private EvolutionSkillMapper skillMapper;

    private SkillRetriever retriever;

    private static final String TENANT = "default";

    /**
     * {@code SkillRetriever} 用「构造器 + 字段」混合注入，Mockito 的 @InjectMocks 在存在构造器时
     * 不会再去注入字段（会让 vectorStore 静默为 null，测试变成假绿）。这里显式装配。
     */
    @BeforeEach
    void setUp() throws Exception {
        retriever = new SkillRetriever(props, meterRegistry);
        inject("vectorStore", vectorStore);
        inject("skillMapper", skillMapper);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field field = SkillRetriever.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(retriever, value);
    }

    private void enabled() {
        when(props.isEnabled()).thenReturn(true);
        when(props.getSkillTopK()).thenReturn(3);
    }

    private static Document doc(String text, Map<String, Object> metadata) {
        return new Document(text, metadata);
    }

    /** 核心回归：向量检索抛异常时必须降级为空上下文，不得向上抛出。 */
    @Test
    void vectorSearchFailureMustDegradeInsteadOfPropagating() {
        enabled();
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("I/O error on POST request for \"https://dashscope.aliyuncs.com/...\": "
                        + "Remote host terminated the handshake"));

        String context = assertDoesNotThrow(() -> retriever.retrieveAsContext("你好", TENANT));

        assertEquals("", context, "检索不可用时不得注入任何上下文");
    }

    /** 降级必须可观测：计入 skill.retrieve.degraded，便于区分「没命中」与「检索挂了」。 */
    @Test
    void degradationMustBeCounted() {
        enabled();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("boom"));
        when(meterRegistry.counter("skill.retrieve.degraded")).thenReturn(degradedCounter);

        retriever.retrieveAsContext("你好", TENANT);

        verify(degradedCounter).increment();
    }

    /** 指标上报自身抛异常时仍必须返回空上下文（降级路径不可被二次异常击穿）。 */
    @Test
    void metricFailureMustNotDefeatDegradation() {
        enabled();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("boom"));
        when(meterRegistry.counter("skill.retrieve.degraded"))
                .thenThrow(new IllegalStateException("registry closed"));

        String context = assertDoesNotThrow(() -> retriever.retrieveAsContext("你好", TENANT));

        assertEquals("", context);
    }

    /** 只有 source=evolution 的文档才算技能；知识库 / 记忆块必须被过滤掉。 */
    @Test
    void nonEvolutionDocumentsAreFilteredOut() {
        enabled();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("知识库正文", Map.of("source", "knowledge")),
                doc("记忆正文", Map.of("source", "memory"))));

        String context = retriever.retrieveAsContext("你好", TENANT);

        assertEquals("", context, "非技能文档不得进入注入上下文");
    }

    /** evolution.enabled=false 时直接返回空串，不应触碰向量库与技能表。 */
    @Test
    void disabledEvolutionSkipsVectorStoreEntirely() {
        when(props.isEnabled()).thenReturn(false);

        assertEquals("", retriever.retrieveAsContext("你好", TENANT));
        verifyNoInteractions(vectorStore);
        verify(skillMapper, never()).selectById(any());
    }

    /** 向量库缺失（可选依赖未装配）时同样只返回空串。 */
    @Test
    void missingVectorStoreSkipsSearch() {
        enabled();
        SkillRetriever withoutStore = new SkillRetriever(props, meterRegistry);

        assertEquals("", withoutStore.retrieveAsContext("你好", TENANT));
        verifyNoInteractions(vectorStore);
    }

    /** 正常路径：evolution 来源且已审核的技能应被格式化为可注入上下文。 */
    @Test
    void approvedEvolutionSkillIsInjected() {
        enabled();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("先共情再建议：先接住情绪，再谈方法", Map.of(
                        "source", "evolution", "skillId", "7",
                        "skillName", "先共情再建议", "content", "先接住情绪，再谈方法"))));
        EvolutionSkill skill = new EvolutionSkill();
        skill.setAuditStatus("APPROVED");
        when(skillMapper.selectById(7L)).thenReturn(skill);

        String context = retriever.retrieveAsContext("怎么安慰对方", TENANT);

        assertTrue(context.contains("【已学经验】"), "应输出已学经验段");
        assertTrue(context.contains("先共情再建议"), "注入内容应含技能名");
    }

    /** 未审核技能不得注入（审核状态前置，06 §5）。 */
    @Test
    void pendingEvolutionSkillIsNotInjected() {
        enabled();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("草稿技能", Map.of("source", "evolution", "skillId", "9",
                        "skillName", "草稿技能", "content", "未审核内容"))));
        EvolutionSkill skill = new EvolutionSkill();
        skill.setAuditStatus("PENDING");
        when(skillMapper.selectById(9L)).thenReturn(skill);

        assertEquals("", retriever.retrieveAsContext("怎么安慰对方", TENANT));
    }

    /** 空白租户兜底为 default（多租户隔离前置），且不改变检索调用。 */
    @Test
    void blankTenantFallsBackToDefault() {
        enabled();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        assertEquals("", retriever.retrieveAsContext("你好", "   "));
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }
}
