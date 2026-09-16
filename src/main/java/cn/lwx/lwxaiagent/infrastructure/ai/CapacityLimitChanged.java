package cn.lwx.lwxaiagent.infrastructure.ai;

/**
 * 自适应并发上限变更事件（ADR-32）。
 *
 * <p>由 {@link LlmGateway} 在 AIMD 收缩/回升后发布，准入层（{@code OnlineLoadTracker}）据此
 * 对齐自己的天花板。<b>为什么必须对齐</b>：系统里有两层并发闸门——准入闸门
 * （{@code app.online.max-inflight}，带 {@code wait-ms} 有界排队）与网关闸门
 * （{@code app.llm.max-concurrent-calls}，厂商侧在途）。ADR-29 把两者都设为 24 使它们看起来冗余；
 * 一旦只让网关自适应而准入仍按 24 放行，多出的请求会在网关 {@code tryAcquire} 失败，
 * 被归一成本地 4003「AI 服务繁忙」——厂商的 429 只是被换成了自家的拒绝，用户可见失败率反而升高。
 *
 * <p>用事件而非直接依赖，是为了不让 {@code infrastructure.ai} 反向依赖
 * {@code infrastructure.scheduler}。
 */
public record CapacityLimitChanged(int limit) {
}
