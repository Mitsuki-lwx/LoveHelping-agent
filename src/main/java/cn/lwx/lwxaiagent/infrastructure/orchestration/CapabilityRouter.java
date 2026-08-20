package cn.lwx.lwxaiagent.infrastructure.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>能力路由器 —— 判断"需要哪些增强能力 + 多深"</h1>
 *
 * <p>纯规则匹配，零 LLM 成本。判断两件事：</p>
 * <ol>
 *   <li><b>能力需求</b>：需要工具？需要 RAG？（返回 boolean 标志）</li>
 *   <li><b>深度</b>：浅层（一次 LLM 调用）vs 深层（多步循环，ReactAgent）</li>
 * </ol>
 *
 * <p>路由器只返回"需求信号"（布尔值），不持有工具实例或 Advisor 对象。
 * {@link cn.lwx.lwxaiagent.infrastructure.orchestration.ChatEntry} 根据需求信号
 * 填充实际的 {@link CapabilitySet}（注入工具列表、创建 RAG advisor）。</p>
 *
 * <p>规则渐进式设计：当前只实现工具/意图/RAG/深度判断。
 * emotion/proactive 为未来占位（false）。</p>
 */
@Slf4j
@Component
public class CapabilityRouter {

    /**
     * 路由结果：能力需求信号 + 深度。
     */
    public record RoutingResult(boolean needRag, boolean needTools, Depth depth) {}

    /**
     * 深度：SHALLOW = 一次 LLM 调用；DEEP = ReactAgent 多步循环。
     */
    public enum Depth { SHALLOW, DEEP }

    /**
     * 路由判断入口。
     *
     * @param message  用户消息文本
     * @param mediaIds 图片 ID（非空 = 视觉模式 → 强制 tools=true）
     * @return 路由结果（能力需求信号 + 深度）
     */
    public RoutingResult resolve(String message, List<Long> mediaIds) {
        boolean vision = mediaIds != null && !mediaIds.isEmpty();
        boolean needTools = vision || hasToolIntent(message);
        boolean needRag = !needTools && hasKnowledgeIntent(message);
        Depth depth = isComplex(message) ? Depth.DEEP : Depth.SHALLOW;

        log.debug("CapabilityRouter: tools={}, rag={}, vision={}, depth={}", needTools, needRag, vision, depth);
        return new RoutingResult(needRag, needTools, depth);
    }

    /**
     * 工具意图检测：搜索/查一下/天气/规划/帮我做/下载/生成PDF 等。
     */
    private boolean hasToolIntent(String message) {
        return message.matches("(?i).{0,5}(搜索|查一下|查查|查询|天气|地图|约会方案|帮我做|帮我规划|帮我下载|下载图片|生成PDF|生成文件|搜索图片).{0,30}");
    }

    /**
     * 知识意图检测：怎么/为什么/建议/如何（无工具意图时触发 RAG）。
     * 互斥：有工具意图时不启 RAG（工具里已含 KnowledgeSearchTool，避免双重检索）。
     */
    private boolean hasKnowledgeIntent(String message) {
        return message.matches("(?i).{0,5}(怎么|如何|为什么|怎么回事|建议|请教|咨询|指导|方法|技巧).{0,30}");
    }

    /**
     * 深度判断：是否需要多步推理？
     *
     * <p>规则（渐进式）：</p>
     * <ul>
     *   <li>多步骤意图词（"帮我做…方案"、"分析…并给出…"）→ DEEP</li>
     *   <li>单步工具意图 / 知识类问题 → SHALLOW</li>
     *   <li>默认 → SHALLOW（安全回退）</li>
     * </ul>
     */
    private boolean isComplex(String message) {
        return message.matches("(?i).{0,5}(帮我|规划|方案|综合|分析.{0,10}并|制定|制定一个|做一个).{0,20}(方案|建议|计划|策略|报告|总结|方案)");
    }
}
