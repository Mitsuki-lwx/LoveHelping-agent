package cn.lwx.lwxaiagent.infrastructure.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatExecutor 三牌切片（FR-CORE-01）单元测试：结构化 advice 事件的解析与降级。
 */
class ChatExecutorTierSliceTest {

    @Test
    void sliceTiers_parsesThreeTiersWithNames() {
        String text = """
                给你三个选择：
                🛡️ 安全牌（保守）: 发一句"最近还好吗"，不施压。
                  对方可能反应：可能会简单回复近况。
                ⚡ 进击牌（主动）: 直接约周末见面，表达想念。
                  对方可能反应：可能答应，也可能需要时间。
                🌸 后撤牌（给空间）: 先不打扰，给她空间。
                  对方可能反应：可能主动来找你。
                """;
        List<ChatExecutor.AdviceTier> tiers = ChatExecutor.sliceTiers(text);
        assertEquals(3, tiers.size());
        assertEquals("安全牌", tiers.get(0).name());
        assertEquals("进击牌", tiers.get(1).name());
        assertEquals("后撤牌", tiers.get(2).name());
        assertTrue(tiers.get(0).content().contains("最近还好吗"), "content 应含具体话术");
        assertTrue(tiers.get(0).reaction().startsWith("对方可能反应"), "reaction 应含对方可能反应");
    }

    @Test
    void sliceTiers_noMarkers_returnsEmpty() {
        String text = "只是普通的一段回复，没有任何话术标记。";
        assertTrue(ChatExecutor.sliceTiers(text).isEmpty(), "无三牌标记应降级为纯文本");
    }

    @Test
    void sliceTiers_onlyTwoTiers_stillParsedForActivation() {
        String text = "🛡️ 安全牌（保守）: 慢慢来。\n⚡ 进击牌（主动）: 主动约。";
        assertEquals(2, ChatExecutor.sliceTiers(text).size());
    }

    @Test
    void sliceTiers_blankOrNull_returnsEmpty() {
        assertTrue(ChatExecutor.sliceTiers(null).isEmpty());
        assertTrue(ChatExecutor.sliceTiers("  ").isEmpty());
    }

    @Test
    void sliceTiers_promiseSentence_shortBodiesSkipped() {
        // 模型只说"我会给你🛡️安全牌、⚡进击牌、🌸后撤牌"——承诺句，非真三牌，不应激活
        String text = "等你补充完细节，我会给你三套方案：🛡️安全牌、⚡进击牌、🌸后撤牌。";
        assertTrue(ChatExecutor.sliceTiers(text).isEmpty(), "承诺句的短牌名不应算有效 tier");
    }

    @Test
    void sliceTiers_noReactionKeyword_keepsWholeBodyAsContent() {
        String text = "🛡️ 安全牌（保守）: 说一句「今晚要不要一起吃饭」。";
        List<ChatExecutor.AdviceTier> tiers = ChatExecutor.sliceTiers(text);
        assertEquals(1, tiers.size());
        assertEquals("", tiers.get(0).reaction());
        assertTrue(tiers.get(0).content().contains("一起吃饭"));
    }
}