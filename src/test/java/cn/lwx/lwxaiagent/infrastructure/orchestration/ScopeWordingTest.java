package cn.lwx.lwxaiagent.infrastructure.orchestration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScopeWording}（ADR-53 / phase21）单元测试。
 *
 * <p>核心不变量：<b>默认必须是 {@code adjacent-help}</b>。若默认值被误改成 {@code strict}，
 * 线上会静默回到"无谓拒答"——而这个回退<b>不报错、不降级、指标全绿</b>，
 * 没有任何现有自动化会发现它（本仓反复踩的"结构性缺陷伪装成正常"形状）。
 */
class ScopeWordingTest {

    @AfterEach
    void resetToDefault() {
        // ScopeWording 的生效值是静态的（启动期装配一次）→ 每个测试后复位，
        // 否则一个 strict 测试会让后续测试在错误的前提下变绿。
        new ScopeWording(ScopeWording.ADJACENT_HELP);
    }

    @Test
    @DisplayName("默认取值 = adjacent-help（生产行为不得被开关悄悄改掉）")
    void defaultIsAdjacentHelp() {
        new ScopeWording(ScopeWording.ADJACENT_HELP);
        assertEquals(ScopeWording.ADJACENT_HELP, ScopeWording.current());
    }

    @Test
    @DisplayName("@Value 的兜底默认值也是 adjacent-help（改 yml/注解默认值时单测能察觉）")
    void fallbackDefaultInAnnotationIsAdjacentHelp() {
        var param = ScopeWording.class.getConstructors()[0].getParameters()[0];
        var value = param.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
        assertTrue(value != null, "scope-wording 必须用 @Value 注入");
        // 字面值是 ${app.chat.scope-wording:adjacent-help} —— 冒号后是兜底默认值
        String expression = value.value();
        assertTrue(expression.startsWith("${") && expression.endsWith("}"),
                "@Value 应当是 ${key:default} 形态，实际=" + expression);
        String fallback = expression.substring(expression.lastIndexOf(':') + 1, expression.length() - 1);
        assertEquals(ScopeWording.ADJACENT_HELP, fallback,
                "@Value 的兜底默认值必须是 adjacent-help，实际=" + fallback);
    }

    @Test
    @DisplayName("两版措辞都能组装出完整 prompt，且内容真的不同（否则 strict 臂等于没切）")
    void twoWordingsProduceDistinctPrompts() {
        String strict = ScopeWording.activeSystemPrompt(ScopeWording.STRICT);
        String adjacent = ScopeWording.activeSystemPrompt(ScopeWording.ADJACENT_HELP);
        assertNotEquals(strict, adjacent, "两版必须真的不同");
        assertTrue(adjacent.contains("不要硬拒"), "adjacent-help 版应含「不要硬拒」");
        assertTrue(!strict.contains("不要硬拒"), "strict 版不应含「不要硬拒」");
        assertTrue(strict.contains("必须用一句话礼貌"), "strict 版应含原「一律礼貌拒绝」措辞");
        assertTrue(adjacent.contains("失眠"), "adjacent-help 版应显式点名失眠（修复的目标议题）");
    }

    @Test
    @DisplayName("拆分没丢段：两版都以 HEAD 开头、TAIL 结尾，且保留全部小节标题")
    void bothWordingsKeepHeadAndTail() {
        for (String w : new String[]{ScopeWording.STRICT, ScopeWording.ADJACENT_HELP}) {
            String p = ScopeWording.activeSystemPrompt(w);
            assertTrue(p.startsWith(ChatExecutor.SYSTEM_PROMPT_HEAD), w + " 应以 HEAD 开始");
            assertTrue(p.endsWith(ChatExecutor.SYSTEM_PROMPT_TAIL), w + " 应以 TAIL 结束");
            for (String section : new String[]{"【Answer-Type Routing】", "【Counter-Question Principle】",
                    "【Three-Tier Advice】", "【Tool Use - Knowledge First】",
                    "【角色与领域边界（Scope）】", "【Confidentiality】"}) {
                assertTrue(p.contains(section), w + " 丢了小节：" + section);
            }
        }
    }

    @Test
    @DisplayName("SYSTEM_PROMPT 常量 = HEAD + ADJACENT_HELP + TAIL（与运行期默认一致）")
    void systemPromptConstantMatchesDefault() {
        assertEquals(ScopeWording.activeSystemPrompt(ScopeWording.ADJACENT_HELP), ChatExecutor.SYSTEM_PROMPT);
    }

    @Test
    @DisplayName("非法取值 → 启动失败（不静默回退，否则对照臂会「以为切了其实没切」）")
    void illegalWordingFailsFast() {
        assertThrows(IllegalStateException.class, () -> new ScopeWording("strict-ish"));
        assertThrows(IllegalStateException.class, () -> new ScopeWording("ADJACENT-HELP")); // 大写不接受
        assertThrows(IllegalStateException.class, () -> new ScopeWording(""));
    }

    @Test
    @DisplayName("strict 臂自报带「对照臂」标记（防止有人误把它当生产配置）")
    void strictIsLabelledAsControlArm() {
        new ScopeWording(ScopeWording.STRICT);
        assertTrue(ScopeWording.describe().contains("对照臂"), "strict 自报必须标出是对照臂");
        new ScopeWording(ScopeWording.ADJACENT_HELP);
        assertTrue(ScopeWording.describe().contains("生产默认"), "adjacent-help 自报应为生产默认");
    }

    @Test
    @DisplayName("⚠️ 接线守护：运行期三处都必须读 activeSystemPrompt（防「改了一处、漏了两处」）")
    void allRuntimePathsUseActivePrompt() {
        // 记忆第 ②/⑪ 条：实现对了 ≠ 接上了。
        // SYSTEM_PROMPT 是 public static 且有 4 处引用；只改 ChatExecutor 而漏掉
        // AgentRegistry / AgentLlmNode，会让对照臂**只在一半路径生效**——
        // 那种失效不报错、不降级、指标全绿，结论会全错且无痕。
        for (String cls : new String[]{
                "cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor",
                "cn.lwx.lwxaiagent.infrastructure.orchestration.AgentRegistry",
                "cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.AgentLlmNode"}) {
            assertTrue(classFileContains(cls, "activeSystemPrompt"),
                    cls + " 没有引用 ScopeWording.activeSystemPrompt() —— 对照臂会「部分路径没切」");
        }
        // 反向：运行期三处都不该再直接 getstatic SYSTEM_PROMPT（那会绕过开关）。
        // PromptVersionService 仍应读常量（它记录的是"产品提示词基准"，与运行期配置无关），
        // 所以只对这三处断言。
        for (String cls : new String[]{
                "cn.lwx.lwxaiagent.infrastructure.orchestration.AgentRegistry",
                "cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.AgentLlmNode"}) {
            assertFalse(classFileContains(cls, "SYSTEM_PROMPT"),
                    cls + " 仍读 ChatExecutor.SYSTEM_PROMPT 常量（写死措辞版本，绕过开关）");
        }
    }

    /** 扫 class 字节码里的方法/字段名引用（常量池一定含被调用的名字） */
    private static boolean classFileContains(String cls, String symbolName) {
        try (var in = ScopeWordingTest.class.getResourceAsStream("/" + cls.replace('.', '/') + ".class")) {
            if (in == null) throw new IllegalStateException("读不到 class 文件：" + cls);
            String s = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return s.contains(symbolName);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取 class 失败：" + cls, e);
        }
    }
}
