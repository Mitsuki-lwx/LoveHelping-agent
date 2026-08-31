package cn.lwx.lwxaiagent.infrastructure.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 能力路由器 —— 只判断"需不需要工具"。
 * 需要→走 Agent（ReactAgent 多步循环）；不需要→走普通（ChatClient 一次 LLM）。
 * 纯规则匹配，零 LLM 成本。
 */
@Slf4j
@Component
public class CapabilityRouter {

    /**
     * 路由判断：当前消息是否需要工具？
     * @param message  用户消息
     * @param mediaIds 图片 ID（非空 = 强制需要工具，走 Agent）
     */
    public boolean needTools(String message, List<Long> mediaIds) {
        if (mediaIds != null && !mediaIds.isEmpty()) return true;
        return hasToolIntent(message);
    }

    /** 工具意图检测：搜索/查一下/天气/规划/帮我做等 */
    private boolean hasToolIntent(String message) {
        return message.matches("(?i).{0,5}(搜索|查一下|查查|查询|天气|地图|约会方案|帮我做|帮我规划|帮我下载|下载图片|生成PDF|生成文件|搜索图片).{0,30}");
    }

    /**
     * 话术建议意图检测（FR-CORE-01）：沟通建议请求 → 触发话术三级。
     * 与 {@link #needTools} 相互独立：话术请求不需要工具，仍走 ChatExecutor。
     * 启发式规则（ADR-18 代价项：可能漏触发/误触发，靠"先澄清问题"原则对冲）——
     * 刻意避开"怎么说/说什么/怎么回"等会被"这个单词怎么说/怎么回家"撞中的宽泛词。
     */
    public boolean isAdviceRequest(String message) {
        return message.matches("(?i).*(怎么回复|如何回复|怎么回她|怎么回他|怎么回消息|怎么哄|怎么道歉|怎么开口|如何开口|怎么拒绝她|怎么拒绝他|怎么表白|怎么挽回|怎么搭讪|怎么接话|怎么继续聊|怎么聊下去|开场白|怎么让话题).*");
    }

    /**
     * 简单问题判定（ADR-19 CAP-2）：问候/感谢/短情绪句 → 走最短路径直接回答。
     * 规则：不命中工具意图与话术请求 + 长度受限 + 无疑问词/检索词 + 匹配正例关键词或短句。
     * 误判面小（最坏情况 = 普通对话节点等价输出），由检查节点兜底。
     */
    public boolean isSimpleQuestion(String message) {
        if (message == null || message.isBlank()) return false;
        if (isAdviceRequest(message) || needTools(message, List.of())) return false;
        String m = message.trim();
        if (m.length() > 24) return false;
        // 疑问/检索诉求不简单（"帮我查/有没有/哪家/多久"等）
        if (m.matches(".*(怎么|如何|为什么|为啥|什么|能不能|是否|该怎么办|多少|哪里|几点|有没有|有没有|帮我|查).*")) return false;
        // 问候/感谢/告别
        if (m.matches("(?i)(你好|您好|嗨|哈喽|hello|hi|hey|早上好|中午好|晚上好|早安|晚安|谢谢|谢谢你|感谢|再见|拜拜|好的|嗯|嗯嗯|加油|辛苦了|辛苦啦|知道啦|明白了|没问题)([!！。~～\\s]*[你您们]?)?!?")) return true;
        // 短情绪句（≤12 字，无问句结构）
        return m.matches("[\\u4e00-\\u9fff，。！？!?、\\s]{1,12}")
                && !m.contains("?") && !m.contains("？");
    }
}