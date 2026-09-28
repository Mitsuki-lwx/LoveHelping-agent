package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.harness.governance.GuardrailMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 流式出站护栏单测（ADR-55 / D1）。
 *
 * <p>核心不变量：<b>命中 L3 的那段文本不得推送</b>，且后续 chunk 全部丢弃、
 * 只推一次替换文案。—— 这是"用户不会看到违规内容"的直接保证；
 * 改造前的缺陷正是"图末端事后替换，而正文早已流出"。</p>
 */
class StreamSinkGuardrailTest {

    @SuppressWarnings("unchecked")
    private FluxSink<String> mockSink() {
        return mock(FluxSink.class);
    }

    private List<String> recording(FluxSink<String> raw) {
        List<String> emitted = new ArrayList<>();
        doAnswer(inv -> { emitted.add(inv.getArgument(0)); return raw; }).when(raw).next(anyString());
        return emitted;
    }

    /** 命中"伤害自己"的判定桩（不依赖 DB） */
    private static final StreamRegistry.OutputGuardrail HIT_ON_INCITE =
            text -> text.contains("伤害自己") ? "self_harm_incite" : null;

    @Test
    @DisplayName("命中：违规文本不推送，改推替换文案；后续 chunk 全部丢弃")
    void blockedTextIsNotEmitted() {
        FluxSink<String> raw = mockSink();
        List<String> out = recording(raw);
        var s = new StreamRegistry.StreamSink(raw, "discard", HIT_ON_INCITE);

        s.append("先说几句安慰的话。");
        s.append("你可以试试伤害自己。");      // ← 命中
        s.append("后面还有一大段违规内容。");   // ← 应被丢弃
        s.flush();

        String all = String.join("", out);
        assertFalse(all.contains("伤害自己"), "违规文本不得送达用户：" + all);
        assertFalse(all.contains("后面还有"), "拦截后的 chunk 必须全部丢弃：" + all);
        assertTrue(all.contains("先说几句安慰的话"), "命中之前的正常文本应保留：" + all);
        assertTrue(all.endsWith(GuardrailMessages.SELF_HARM_OUTPUT), "应推替换文案：" + all);
        assertTrue(s.guardrailBlocked(), "应置拦截标志（ChatEntry 据此避免重复推）");
    }

    @Test
    @DisplayName("替换文案只推一次（flush 不重复推）")
    void replacementEmittedExactlyOnce() {
        FluxSink<String> raw = mockSink();
        List<String> out = recording(raw);
        var s = new StreamRegistry.StreamSink(raw, "discard", HIT_ON_INCITE);
        s.append("你可以试试伤害自己。");
        s.flush();
        s.flush();
        long n = out.stream().filter(t -> t.equals(GuardrailMessages.SELF_HARM_OUTPUT)).count();
        assertEquals(1, n, "替换文案应恰好推一次，实际 " + n + " 次");
    }

    @Test
    @DisplayName("⭐ 关键词跨 chunk：'伤害' 与 '自己' 分两次到达也要命中")
    void keywordSplitAcrossChunksIsCaught() {
        FluxSink<String> raw = mockSink();
        List<String> out = recording(raw);
        var s = new StreamRegistry.StreamSink(raw, "discard", HIT_ON_INCITE);

        s.append("你可以试试伤害");   // 半个词
        s.append("自己。");           // 补全 → 尾部窗口拼起来才命中
        s.flush();

        String all = String.join("", out);
        assertTrue(s.guardrailBlocked(), "跨 chunk 的关键词也必须被拦住，实际输出：" + all);
        assertFalse(all.contains("自己。"), "拼起来命中后，补全的那块也不该送达：" + all);
    }

    @Test
    @DisplayName("未命中：文本照常全量送达，行为与改造前一致")
    void notHitPassesThrough() {
        FluxSink<String> raw = mockSink();
        List<String> out = recording(raw);
        var s = new StreamRegistry.StreamSink(raw, "discard", HIT_ON_INCITE);
        s.append("朋友情绪低落时，先接住他的感受，不要急着给建议。");
        s.flush();
        assertEquals("朋友情绪低落时，先接住他的感受，不要急着给建议。", String.join("", out));
        assertFalse(s.guardrailBlocked());
        assertTrue(s.streamed());
    }

    @Test
    @DisplayName("护栏为 null（单测/兼容构造）：不检查，行为等于 ADR-55 之前")
    void nullGuardrailDisablesCheck() {
        FluxSink<String> raw = mockSink();
        List<String> out = recording(raw);
        var s = new StreamRegistry.StreamSink(raw, "discard");   // 旧两参构造
        s.append("你可以试试伤害自己。");
        s.flush();
        assertEquals("你可以试试伤害自己。", String.join("", out), "无护栏时不应拦截");
        assertFalse(s.guardrailBlocked());
    }

    @Test
    @DisplayName("判定抛异常 → fail-open 放行（不因护栏故障阻断对话）")
    void guardrailFailureFailsOpen() {
        FluxSink<String> raw = mockSink();
        List<String> out = recording(raw);
        StreamRegistry.OutputGuardrail broken = text -> { throw new IllegalStateException("boom"); };
        var s = new StreamRegistry.StreamSink(raw, "discard", broken);
        s.append("正常内容。");
        s.flush();
        assertEquals("正常内容。", String.join("", out), "护栏故障必须放行，否则是可用性事故");
        assertFalse(s.guardrailBlocked());
    }

    @Test
    @DisplayName("文案按规则选：非自伤规则不给自伤转介")
    void messageSelectedByRule() {
        assertEquals(GuardrailMessages.SELF_HARM_OUTPUT, GuardrailMessages.forRule("self_harm"));
        assertEquals(GuardrailMessages.SELF_HARM_OUTPUT, GuardrailMessages.forRule("self_harm_incite"));
        assertEquals(GuardrailMessages.OTHER_OUTPUT, GuardrailMessages.forRule("manipulation_intent"));
        assertEquals(GuardrailMessages.OTHER_OUTPUT, GuardrailMessages.forRule(null));
    }

    @Test
    @DisplayName("flush 的残留文本也过护栏（不能只拦 append 路径）")
    void flushRemainderIsGuarded() {
        FluxSink<String> raw = mockSink();
        List<String> out = recording(raw);
        var s = new StreamRegistry.StreamSink(raw, "discard", HIT_ON_INCITE);
        s.append("你可以试试伤害自己");   // 不含句号，仍会在 append 时命中
        s.flush();
        assertFalse(String.join("", out).contains("伤害自己"), "flush 残留不得绕过护栏");
    }
}
