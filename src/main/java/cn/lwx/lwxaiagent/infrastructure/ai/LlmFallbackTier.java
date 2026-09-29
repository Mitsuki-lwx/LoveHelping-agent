package cn.lwx.lwxaiagent.infrastructure.ai;

import org.springframework.ai.chat.model.ChatModel;

import java.util.Objects;

/**
 * ADR-52：**一个可注册的降级级**。
 *
 * <p>由配置装配类产出（ADR-58：{@code LlmProviderConfig} 从 {@code app.llm.providers} 构建），
 * {@link LlmGateway} 经 {@link LlmProviderChain} 拿到整条链。
 * <b>配置里写几条就有几级</b>——网关不再有"具名槽位"，加一级只需加一条配置。</p>
 *
 * <p><b>为什么是 record 而不是接口</b>：tier 是纯数据（名字 + 模型 + 报告用端点），无行为。
 * 每级的<b>熔断器由网关自己建</b>（{@code Map<String, ProviderCircuit>}）——
 * 熔断器是有状态资源，让配置类持有它会引入"谁负责 close"的额外问题。</p>
 *
 * <p><b>为什么 {@code baseUrl} 只用于报告</b>：它是给
 * {@code LlmGateway#endpointBaseUrl} 拼"生效端点"指标用的（ADR-48 的可观测性缺口），
 * <b>不参与请求</b>。请求走哪个 URL 由各 {@code ChatModel} 自己决定。
 * 拆成独立字段是为了让 {@code endpointBaseUrl} 不再按 {@code "fallback"} /
 * {@code "last-resort"} 这种<b>字面量</b>分支（ADR-51 §已知限制 口子 3）。</p>
 *
 * @param name    级别标识，进指标 tag 与日志（如 {@code fallback} / {@code last-resort}），不可为空
 * @param model   该级的聊天模型，不可为 null
 * @param baseUrl 该级的端点前缀，<b>仅用于报告</b>；未知/无则传 {@code null} 或 {@code ""}
 * @author lwx
 */
public record LlmFallbackTier(String name, ChatModel model, String baseUrl) {

    public LlmFallbackTier {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("降级级名字不能为空 —— 它是指标 tag 与日志标识");
        }
        Objects.requireNonNull(model, "降级级的 ChatModel 不能为 null");
        baseUrl = (baseUrl == null) ? "" : baseUrl;
    }

    /** 便捷构造：不需要报告端点时。 */
    public LlmFallbackTier(String name, ChatModel model) {
        this(name, model, "");
    }
}
