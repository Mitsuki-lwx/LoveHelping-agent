package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.InsightRecord;
import cn.lwx.lwxaiagent.mapper.InsightRecordMapper;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import cn.lwx.lwxaiagent.infrastructure.ai.VisionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>沟通模式分析（此前覆盖率 0%）</h3>
 *
 * <p>这段代码同时踩着**两条本仓最在意的线**，而此前一行都没测：</p>
 * <ul>
 *   <li><b>伦理红线</b>（SRS §5.3）：prompt 里明令禁止「障碍/人格/诊断/依恋/焦虑症/抑郁症」，
 *       但模型不听话 —— 所以必须有<b>输出侧检测</b>，命中就挂 warning。这一层丢了，
 *       产品就可能把"诊断"直接说给用户；</li>
 *   <li><b>归属校验</b>（docs/07 §3）：分析记录是**用户私密数据**，
 *       越权读到别人的分析结果就是数据泄露。</li>
 * </ul>
 * <p>另钉两条工程契约：JSON 被模型包在 ```json 围栏里要能剥出来；超长输入要截断（否则 token 爆）。</p>
 */
@DisplayName("InsightService：伦理防线与归属校验")
class InsightServiceTest {

    private ChatModel chatModel;
    private InsightRecordMapper recordMapper;
    private InsightService service;

    private static final String GOOD_JSON =
            "{\"statistics\":{\"turns\":\"12\"},\"patterns\":[\"他总是先结束话题\"],\"suggestions\":[\"试试先问感受\"]}";

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        VisionPort vision = mock(VisionPort.class);
        MessageMediaMapper mediaMapper = mock(MessageMediaMapper.class);
        recordMapper = mock(InsightRecordMapper.class);
        // ⛔ 构造器**没有 ObjectMapper 形参**（服务内部自建）—— 我第一版按字段猜的，签名不符
        service = new InsightService(chatModel, vision, mediaMapper, recordMapper);
    }

    private void modelSays(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(text))),
                        new ChatResponseMetadata()));
    }

    @Test
    @DisplayName("空输入 → 直接 400（不花钱调模型）")
    void blank_input_rejected_before_llm() {
        assertThatThrownBy(() -> service.analyze("   ", "chat"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("聊天记录不能为空");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("⛔ 输出含**诊断性语言** → 结果里挂 warning（伦理红线：不做心理诊断）")
    void diagnostic_language_triggers_warning() {
        modelSays("{\"statistics\":{\"turns\":\"12\"},\"patterns\":[\"他这是回避型依恋人格，可能有焦虑症\"],\"suggestions\":[\"去看医生\"]}");

        Map<String, Object> result = service.analyze("聊天记录片段", "chat");

        assertThat(result)
                .as("模型不听话时，这层是最后一道：必须在结果上留痕，不能让它直接说给用户")
                .containsKey("warning");
        assertThat((String) result.get("warning")).contains("诊断性语言");
    }

    @Test
    @DisplayName("输出干净 → 不挂 warning（别把正常分析也标成风险）")
    void clean_output_has_no_warning() {
        modelSays(GOOD_JSON);

        assertThat(service.analyze("聊天记录片段", "chat")).doesNotContainKey("warning");
    }

    @Test
    @DisplayName("⛔ 永远附带 disclaimer（明确不是心理/关系诊断）")
    void disclaimer_is_always_attached() {
        modelSays(GOOD_JSON);

        Map<String, Object> result = service.analyze("聊天记录片段", "chat");

        assertThat((String) result.get("disclaimer")).contains("不做任何心理或关系诊断");
        assertThat(result.get("sourceType")).isEqualTo("chat");
    }

    @Test
    @DisplayName("模型把 JSON 包在 ```json 围栏里也要能解析（否则整轮分析直接失败）")
    void fenced_json_is_extracted() {
        modelSays("这是分析结果：\n```json\n" + GOOD_JSON + "\n```\n希望有帮助");

        Map<String, Object> result = service.analyze("聊天记录片段", "chat");

        assertThat((Map<String, Object>) result.get("statistics")).containsEntry("turns", "12");
        assertThat(result).containsKey("disclaimer");
    }

    @Test
    @DisplayName("模型输出不是 JSON → 500，且**不把半成品返回给用户**")
    void malformed_output_fails_cleanly() {
        modelSays("抱歉，我无法分析这个内容。");

        assertThatThrownBy(() -> service.analyze("聊天记录片段", "chat"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("分析失败");
    }

    @Test
    @DisplayName("超长输入被截断到 4000 字（防 token 爆），且截断痕迹进了 prompt")
    void long_input_is_truncated() {
        modelSays(GOOD_JSON);
        String huge = "x".repeat(5000);

        service.analyze(huge, "chat");

        ArgumentCaptor<Prompt> cap = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(cap.capture());
        String sent = cap.getValue().getContents();   // Prompt.getContents() 直接给拼好的 String
        assertThat(sent).contains("[后续内容已截断]").doesNotContain("x".repeat(4500));
    }

    @Test
    @DisplayName("⛔ 归属校验：读别人的分析记录 → 403（分析内容是私密数据）")
    void cross_user_read_is_rejected() {
        InsightRecord record = new InsightRecord();
        record.setId(5L);
        record.setUserId("someone_else");
        when(recordMapper.selectById(5L)).thenReturn(record);

        assertThatThrownBy(() -> service.getRecord(5L, "me"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    @DisplayName("记录不存在 → 404（区分'没有'与'不是你的'，别泄露存在性细节）")
    void missing_record_is_404() {
        when(recordMapper.selectById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getRecord(404L, "me"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("记录不存在");
    }

    @Test
    @DisplayName("删除也走归属校验：不是你的记录 → 403 且**不删任何东西**")
    void cross_user_delete_is_rejected() {
        InsightRecord record = new InsightRecord();
        record.setId(5L);
        record.setUserId("someone_else");
        when(recordMapper.selectById(5L)).thenReturn(record);

        assertThatThrownBy(() -> service.deleteRecord(5L, "me"))
                .isInstanceOf(BizException.class);
        verify(recordMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    @DisplayName("自己的记录可以正常删除（隐私闭环 CAP-6）")
    void own_record_can_be_deleted() {
        InsightRecord record = new InsightRecord();
        record.setId(5L);
        record.setUserId("me");
        when(recordMapper.selectById(5L)).thenReturn(record);

        service.deleteRecord(5L, "me");

        verify(recordMapper).deleteById(5L);
    }
}
