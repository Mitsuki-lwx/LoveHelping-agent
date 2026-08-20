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
}