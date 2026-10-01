package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.SentimentLog;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.infrastructure.ai.JevClient;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmGateway;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import cn.lwx.lwxaiagent.mapper.SentimentLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>情绪打分（此前覆盖率 0%）</h3>
 *
 * <p>挑它的理由：它是**确定性逻辑 + 明确的协议解析**（模型必须回 `[SCORE]x[/SCORE]` / `[WHY]…[/WHY]`），
 * 而代码注释自己就承认过这一族风险 ——「旧实现一旦格式跑偏会**静默得 0 分（="平静"）**，
 * 那是"伪装成正常数据"的错误」。这种"静默降级成正常值"的行为必须有测试钉住，
 * 否则将来改格式时没人会发现。</p>
 */
@DisplayName("SentimentService：打分与解析契约")
class SentimentServiceTest {

    private SentimentLogMapper sentimentMapper;
    private MessageMapper messageMapper;
    private EncryptionService encryptionService;
    private LlmGateway llmGateway;
    private JevClient jev;
    private SentimentService service;

    @BeforeEach
    void setUp() {
        sentimentMapper = mock(SentimentLogMapper.class);
        messageMapper = mock(MessageMapper.class);
        encryptionService = mock(EncryptionService.class);
        llmGateway = mock(LlmGateway.class);
        jev = mock(JevClient.class);
        service = new SentimentService(sentimentMapper, messageMapper, encryptionService, llmGateway, jev);
    }

    private SentimentLog captured() {
        ArgumentCaptor<SentimentLog> cap = ArgumentCaptor.forClass(SentimentLog.class);
        verify(sentimentMapper).insert(cap.capture());
        return cap.getValue();
    }

    private void llmSays(String text) {
        when(llmGateway.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                        new ChatResponseMetadata()));
    }

    @Test
    @DisplayName("空/空白输入：既不落库也不调上游（避免把噪声写成情绪数据）")
    void blank_input_is_noop() {
        service.scoreText("u1", "c1", "   ");
        service.scoreText("u1", "c1", null);
        service.scoreText(null, "c1", "你好");
        verify(sentimentMapper, never()).insert(any(SentimentLog.class));
        verify(llmGateway, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("该会话已有记录 → 跳过（幂等；且**不再调一次 LLM**）")
    void existing_row_skips_work() {
        when(sentimentMapper.selectCount(any())).thenReturn(1L);
        service.scoreText("u1", "c1", "今天有点低落");
        verify(sentimentMapper, never()).insert(any(SentimentLog.class));
        verify(llmGateway, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Jev 命中 → 用它的分值与档位落库，且**完全不走 LLM**（省一次调用）")
    void jev_path_wins_and_skips_llm() {
        when(sentimentMapper.selectCount(any())).thenReturn(0L);
        // level 0 → toScore() = -2（档位映射：下标 - 2）
        when(jev.score(any(), any(), any())).thenReturn(Optional.of(new JevClient.Mood(0, "非常糟糕")));

        service.scoreText("u1", "c1", "撑不住了");

        SentimentLog row = captured();
        assertThat(row.getScore()).as("level 0 必须映射到 -2").isEqualTo(-2);
        assertThat(row.getReason()).isEqualTo("非常糟糕");
        verify(llmGateway, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Jev 未命中 → 回落 LLM 并解析 [SCORE]/[WHY]")
    void llm_fallback_parses_tags() {
        when(sentimentMapper.selectCount(any())).thenReturn(0L);
        when(jev.score(any(), any(), any())).thenReturn(Optional.empty());
        llmSays("分析完毕。\n[SCORE]1[/SCORE]\n[WHY]  有些起色  [/WHY]\n");

        service.scoreText("u1", "c1", "今天好一点了");

        SentimentLog row = captured();
        assertThat(row.getScore()).isEqualTo(1);
        assertThat(row.getReason()).as("WHY 内容要 trim，不能带首尾空白").isEqualTo("有些起色");
    }

    @Test
    @DisplayName("⛔ LLM 输出**格式跑偏** → 分值回落 0（钉住这个「伪装成平静」的既有行为）")
    void drifted_format_falls_back_to_zero() {
        when(sentimentMapper.selectCount(any())).thenReturn(0L);
        when(jev.score(any(), any(), any())).thenReturn(Optional.empty());
        llmSays("我觉得对方挺好的，你可能也有点复杂的心情");   // 完全没有 [SCORE] 标记

        service.scoreText("u1", "c1", "说不清");

        SentimentLog row = captured();
        assertThat(row.getScore())
                .as("解析失败会静默记 0=平静 —— 这是**已知代价**，改解析逻辑时这里会红并提醒你")
                .isZero();
        assertThat(row.getReason()).as("没有 WHY 就是 null（不要编一句话充数）").isNull();
    }

    @Test
    @DisplayName("上游异常不抛出（情绪线是增强不是硬依赖，不能带崩对话）")
    void upstream_failure_is_swallowed() {
        when(sentimentMapper.selectCount(any())).thenReturn(0L);
        when(jev.score(any(), any(), any())).thenThrow(new RuntimeException("jev down"));
        assertThatCode(() -> service.scoreText("u1", "c1", "随便说点"))
                .doesNotThrowAnyException();
    }
}
