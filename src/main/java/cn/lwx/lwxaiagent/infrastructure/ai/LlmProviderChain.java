package cn.lwx.lwxaiagent.infrastructure.ai;

import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * <b>LLM 链路（ADR-58）</b>：主链 + 降级级，由 {@code app.llm.providers} 配置驱动装配。
 *
 * <p>为什么用一个显式的记录类型而不是"两个独立 bean"（主链 {@code ChatModel} +
 * {@code ObjectProvider<LlmFallbackTier>}）：
 * <ul>
 *   <li>{@code LlmFallbackTier} 是<b>动态条数</b>的（配置里有几条降级级就有几条），
 *       而 Spring 无法从配置列表直接产出 N 个 bean 而不引入 bean-definition 注册魔法；</li>
 *   <li>{@code ObjectProvider<List<LlmFallbackTier>>} 的解析语义有歧义
 *       （Spring 对 {@code List<T>} 走"收集所有 T bean"的路径，而这里 T 本身就是 List）——
 *       用一个明确类型的 bean 把歧义消灭掉。</li>
 * </ul></p>
 *
 * <p>⛔ <b>准入不变式</b>：{@code primary} 与每个 tier 里都是<b>裸供应商 {@code ChatModel}</b>，
 * 注入本类型等价于绕过网关（并发许可 / 熔断 / 重试预算 / 用量归因全失效）。
 * 合法提及者只有装配者与网关自身，由 {@code AdmissionCompletenessTest} 守护。</p>
 */
public record LlmProviderChain(ChatModel primary, String primaryBaseUrl, List<LlmFallbackTier> tiers) {

    public LlmProviderChain {
        if (primary == null) throw new IllegalArgumentException("LlmProviderChain.primary 不能为 null");
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
    }
}
