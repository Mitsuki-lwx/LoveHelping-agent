package cn.lwx.lwxaiagent.infrastructure.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CapabilityRouter 单元测试：话术请求触发识别（FR-CORE-01）不与工具意图混淆。
 */
class CapabilityRouterTest {

    private final CapabilityRouter router = new CapabilityRouter();

    // ---- 正例：沟通建议请求 ---- //

    @Test
    void isAdviceRequest_positive_cases() {
        for (String msg : List.of(
                "她三天没回消息我怎么回复",
                "怎么回复她的消息",
                "怎么哄她开心",
                "怎么道歉比较好",
                "怎么开口约她出来",
                "怎么表白不尴尬",
                "开场白怎么说好",
                "怎么挽回前任")) {
            assertTrue(router.isAdviceRequest(msg), "应命中话术请求: " + msg);
        }
    }

    // ---- 负例：不能被宽泛词撞中 ---- //

    @Test
    void isAdviceRequest_negative_cases() {
        for (String msg : List.of(
                "今天天气怎么样",
                "怎么回家最快",
                "这个单词怎么说",
                "帮我规划约会",     // 工具意图，非话术
                "查一下明天天气",   // 工具意图
                "我很难受")) {
            assertFalse(router.isAdviceRequest(msg), "不应命中话术请求: " + msg);
        }
    }

    // ---- 与 needTools 相互独立 ---- //

    @Test
    void adviceRequest_doesNotTriggerToolIntent() {
        assertFalse(router.needTools("怎么哄她开心", List.of()),
                "话术请求不需要工具，仍走 ChatExecutor");
    }

    @Test
    void toolIntent_doesNotBecomeAdviceRequest() {
        assertFalse(router.isAdviceRequest("帮我规划约会"));
    }

    // ---- 简单问题判定（ADR-19 CAP-2）---- //

    @Test
    void isSimpleQuestion_positive() {
        for (String msg : List.of("你好", "您好", "嗨", "在吗", "早上好", "谢谢", "谢谢你了", "晚安", "嗯", "辛苦了")) {
            assertTrue(router.isSimpleQuestion(msg), "应判为简单问题: " + msg);
        }
    }

    @Test
    void isSimpleQuestion_shortEmotion() {
        // 短情绪句，无问句、无检索诉求
        assertTrue(router.isSimpleQuestion("我今天很难过"));
        assertTrue(router.isSimpleQuestion("好累啊"));
        assertTrue(router.isSimpleQuestion("最近有点烦"));
    }

    @Test
    void isSimpleQuestion_negative() {
        for (String msg : List.of(
                "今天天气怎么样",       // 工具意图
                "帮我查一下",          // 工具意图
                "怎么回复她",          // 话术请求
                "怎么哄她开心",        // 话术请求
                "有没有适合约会的餐厅",  // 检索诉求
                "为什么她不理我",       // 疑问诉求
                "你能帮我写一篇很长的道歉信吗因为我要好好解释清楚避免误会")) { // 超长
            assertFalse(router.isSimpleQuestion(msg), "不应判为简单问题: " + msg);
        }
    }

    @Test
    void isSimpleQuestion_mutuallyExclusive() {
        // 简单问题绝不同时是工具意图或话术请求
        for (String msg : List.of("你好", "谢谢", "晚安")) {
            assertTrue(router.isSimpleQuestion(msg));
            assertFalse(router.needTools(msg, List.of()));
            assertFalse(router.isAdviceRequest(msg));
        }
    }

    // ---- 2026-09-20 修复：工具意图不再要求"关键词在前 6 字内" ---- //
    // 改前这三条**全部不命中**（matches() 全串匹配 + `.{0,5}` 前缀 = 位置约束）
    // → 被路由到 R_NORMAL，用户要实时信息却拿不到工具。

    @Test
    void toolIntent_longMessageWithKeywordNotAtHead() {
        for (String msg : List.of(
                "北京今天适合户外约会吗？查下天气",
                "帮我搜一下附近适合第一次约会的咖啡馆",
                "2025 年之后离婚冷静期有没有新的政策变化？帮我查证一下")) {
            assertTrue(router.needTools(msg, List.of()), "长句里的工具意图必须命中: " + msg);
        }
    }

    /**
     * 弱主题词（天气/地图/最新…）不得被放宽成 contains ——
     * 情感叙述里提到天气很常见，误判成 Agent 的代价是**多轮 LLM 调用**，比漏判更贵。
     */
    @Test
    void toolIntent_narrationMentioningWeatherIsNotToolIntent() {
        for (String msg : List.of(
                "他跟我说周末天气好的话就带我去海边",
                "我们本来打算去爬山，结果天气不好就取消了",
                "他最近老是不回我消息")) {
            assertFalse(router.needTools(msg, List.of()), "情感叙述不该判成工具意图: " + msg);
        }
    }

    // ---- 2026-09-20 修复：补"求说法/求办法"型话术请求（改前两条都不命中）---- //

    @Test
    void isAdviceRequest_askForWordingOrMethod() {
        for (String msg : List.of(
                "消息老是已读不回，有什么办法能让他主动找我聊",
                "刚认识一周，想约她出来，有什么自然的说法")) {
            assertTrue(router.isAdviceRequest(msg), "求说法/求办法应命中话术请求: " + msg);
        }
    }

    /** "求方法"必须紧跟沟通动作；否则会把域外请求从 normal 拉进话术链。 */
    @Test
    void isAdviceRequest_askMethodWithoutCommunicationVerbStaysOut() {
        for (String msg : List.of(
                "有什么办法提高网速",
                "有没有什么办法把这段代码跑得快点",
                "有什么办法能让他还钱")) {
            assertFalse(router.isAdviceRequest(msg), "非沟通场景的求方法不算话术请求: " + msg);
        }
    }
}