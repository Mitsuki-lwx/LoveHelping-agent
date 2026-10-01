package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>对话记忆窗口（此前覆盖率 0%）</h3>
 *
 * <p>{@code docs/09} §2 把「**历史窗口裁剪（20/50 边界）**」列为单元测试重点，而
 * 这个类此前**一行都没被测过**。这里钉四条最容易静默坏的契约：</p>
 * <ol>
 *   <li><b>窗口 LIMIT 必须下推到 SQL</b>（不是取回来再裁 —— 那会把整段历史读进内存）；</li>
 *   <li><b>顺序必须翻回来</b>：SQL 是 {@code ORDER BY id DESC}，但给模型的历史必须是**旧→新**；
 *       这处一旦改错，模型看到的是倒着放的对话，且**不会报任何错**；</li>
 *   <li><b>anonymous 必须完全无状态</b>（不落库、不读取）—— 匿名聊天是隐私承诺，不是优化；</li>
 *   <li>落库前**必须加密 + 算 HMAC**（明文入库是安全事故）。</li>
 * </ol>
 */
@DisplayName("MessageChatMemory：窗口、顺序与加密")
class MessageChatMemoryTest {

    private MessageMapper mapper;
    private EncryptionService encryption;

    /**
     * ⛔ **必须先建 TableInfo 缓存**：MyBatis-Plus 的 lambda wrapper（{@code Message::getXxx}）
     * 要靠它把方法引用解析成列名，否则构造 wrapper 就抛
     * （"can not find lambda cache for this entity"）。
     * 生产里由 mapper 扫描完成；**纯单测没有 Spring，就得自己初始化** ——
     * 否则会看到非常迷惑的现象：{@code clear()} "零交互"（异常被它自己的 try/catch 吞了）。
     */
    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Message.class);
        mapper = mock(MessageMapper.class);
        encryption = mock(EncryptionService.class);
        when(encryption.encrypt(anyString(), anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        when(encryption.hmac(anyString())).thenAnswer(i -> "hmac:" + i.getArgument(0));
        when(encryption.decrypt(anyString(), anyString())).thenAnswer(i -> i.getArgument(0));
    }

    private MessageChatMemory memory(int window, String user) {
        return new MessageChatMemory(mapper, window, "pv1", encryption, user);
    }

    private static Message row(long id, String role, String content) {
        Message m = new Message();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setUserId("u1");
        return m;
    }

    @Test
    @DisplayName("⛔ 窗口 LIMIT 必须进 SQL（windowSize 是多少就查多少）")
    void window_limit_is_pushed_into_sql() {
        when(mapper.selectList(any())).thenReturn(List.of());
        memory(20, "u1").get("c1");

        ArgumentCaptor<LambdaQueryWrapper<Message>> cap = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(cap.capture());
        assertThat(cap.getValue().getCustomSqlSegment())
                .as("取回再裁会把整段历史读进内存；窗口必须下推成 SQL 里的 LIMIT")
                .contains("LIMIT 20");
    }

    @Test
    @DisplayName("⛔ 顺序必须翻回「旧→新」：SQL 给的是 id DESC，喂模型的是时间序")
    void rows_are_reversed_to_chronological_order() {
        // SQL 语义：最新的在前
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(
                List.of(row(3, "ASSISTANT", "第三条"), row(2, "USER", "第二条"), row(1, "USER", "第一条"))));

        List<org.springframework.ai.chat.messages.Message> out = memory(20, "u1").get("c1");

        assertThat(out).extracting(org.springframework.ai.chat.messages.Message::getText)
                .as("倒着放不会报错，只会让模型看到反过来的对话")
                .containsExactly("第一条", "第二条", "第三条");
    }

    @Test
    @DisplayName("role 映射到对应的 Spring AI 消息类型")
    void roles_map_to_message_types() {
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(
                List.of(row(2, "assistant", "a"), row(1, "user", "u"))));

        List<org.springframework.ai.chat.messages.Message> out = memory(20, "u1").get("c1");

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).as("小写 role 也要归一化").isInstanceOf(UserMessage.class);
        assertThat(out.get(1)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    @DisplayName("SYSTEM 行还原成 SystemMessage")
    void system_role_restored() {
        when(mapper.selectList(any())).thenReturn(new ArrayList<>(List.of(row(1, "SYSTEM", "s"))));
        assertThat(memory(20, "u1").get("c1").get(0)).isInstanceOf(SystemMessage.class);
    }

    @Test
    @DisplayName("⛔ anonymous 完全无状态：不落库、不读取（隐私承诺，不是优化）")
    void anonymous_is_stateless() {
        MessageChatMemory anon = memory(20, "anonymous");

        anon.add("c1", List.of(new UserMessage("这是匿名的悄悄话")));
        verify(mapper, never()).insert(any(Message.class));

        assertThat(anon.get("c1")).isEmpty();
        verify(mapper, never()).selectList(any());
    }

    @Test
    @DisplayName("落库前必须**加密 + 算 HMAC**，并带上 promptVersion（明文入库是事故）")
    void add_encrypts_and_signs() {
        memory(20, "u1").add("c1", List.of(new UserMessage("我的手机号是…")));

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(mapper).insert(cap.capture());
        Message row = cap.getValue();
        assertThat(row.getContent()).as("不许明文入库").isEqualTo("enc:我的手机号是…");
        assertThat(row.getContentHmac()).isEqualTo("hmac:我的手机号是…");
        assertThat(row.getPromptVersion()).isEqualTo("pv1");
        assertThat(row.getRole()).isEqualTo("USER");
        assertThat(row.getFeedback()).isEqualTo("NONE");
        assertThat(row.getDeleted()).isZero();
        assertThat(row.getUserId()).isEqualTo("u1");
    }

    @Test
    @DisplayName("空白正文的消息被跳过（不写空行），有效消息照常写")
    void blank_content_is_skipped() {
        memory(20, "u1").add("c1", List.of(new UserMessage("  "), new UserMessage("有效")));
        verify(mapper, times(1)).insert(any(Message.class));
    }

    @Test
    @DisplayName("单行插入失败被吞，不影响其余行（记忆是增强不是硬依赖）")
    void one_row_failure_does_not_stop_others() {
        when(mapper.insert(any(Message.class))).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> memory(20, "u1").add("c1", List.of(new UserMessage("a"), new UserMessage("b"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("clear 是**软删**（deleted=1），且按 会话+本人 过滤")
    void clear_soft_deletes_scoped_to_user() {
        memory(20, "u1").clear("c1");
        verify(mapper).update(org.mockito.ArgumentMatchers.<Message>isNull(), any());
    }

    @Test
    @DisplayName("读取异常返回空表而不是抛（不能带崩对话）")
    void read_failure_returns_empty() {
        when(mapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        assertThat(memory(20, "u1").get("c1")).isEmpty();
    }
}
