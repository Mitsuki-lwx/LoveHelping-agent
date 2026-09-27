package cn.lwx.lwxaiagent.infrastructure.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Single retry owner and bounded failure budgets (ADR-23). All durations are milliseconds. */
@Data
@Validated
@ConfigurationProperties(prefix = "app.llm")
public class LlmGatewayProperties {
    @Valid private Retry retry = new Retry();
    @Valid private Circuit circuit = new Circuit();
    @Valid private Adaptive adaptive = new Adaptive();
    /**
     * ADR-52：**降级链的运行时总闸**（原名 {@code fallbackEnabled}，改名以消除歧义）。
     *
     * <p>见 {@code LlmGateway#canDegradeTo} / {@code #canFallback}：置 {@code false} 时
     * <b>整条链一级都不走</b>，即使各级 tier bean 都已注册。</p>
     *
     * <p><b>为什么必须改名</b>：本类前缀是 {@code app.llm}，字段名 {@code fallbackEnabled}
     * 会把 yml 的 {@code app.llm.fallback-enabled} <b>顺带绑进来</b>，而同一个 key
     * 又被 {@code ChatModelConfig} 的 {@code @ConditionalOnProperty} 用来决定
     * "dashscope 级 bean 是否注册" —— <b>一个 key 两个不相干的用途</b>，
     * 这正是 ADR-51 §已知限制"口子 1"的机制来源（想只开 bigmodel 却做不到）。
     * 改名后总闸（{@code degrade-enabled}）与两个级开关彻底解耦。</p>
     *
     * <p>默认 {@code true} 是安全语义：降级是否发生由"链里有没有级"决定，
     * 没有级时总闸开着也什么都不做。且既有单测直接 {@code new} 本类做降级用例，
     * 依赖此默认。</p>
     */
    private boolean degradeEnabled = true;
    /**
     * 并发闸门<b>上限</b>（ADR-29 的三层对齐值 + ADR-32 的自适应天花板）。
     * 自适应开启后，实际在途上限由 {@link Adaptive} 在
     * [{@code adaptive.min-concurrent-calls}, 本值] 区间内自行收敛，本值不再是"精确对齐厂商上限"。
     */
    @Min(1) @Max(256) private int maxConcurrentCalls = 24;
    @Min(1) private long attemptTimeoutMs = 25000;
    /**
     * ADR-49：流式「模型有没有起步」的上限，<b>与 {@link #attemptTimeoutMs} 解耦</b>。
     * <p>此前首值超时直接复用了 attempt-timeout（45s）—— 那是**同步整调用**的预算
     * （实测生成 300~500 tok 要 10~17s），拿来当首字节上限就是 9 倍冗余：
     * 真故障时用户先白等 45s 才进降级。</p>
     * <p>实测（scripts/probe_first_token_latency.py，43 例：单线程 19 + 并发 24）：
     * TTFT p50 2.1~2.3s / max 4.95s，并发 24 无排队抬升、0 失败 → 15000 留约 3 倍余量。</p>
     */
    @Min(1) private long firstByteTimeoutMs = 15000;
    @Min(1) private long totalTimeoutMs = 60000;
    @Min(1) private long streamIdleTimeoutMs = 15000;
    @Min(1) private long connectTimeoutMs = 3000;

    @Data
    public static class Retry {
        /** Includes the initial primary attempt; fallback is attempted at most once. */
        @Min(1) @Max(5) private int maxAttempts = 3;
        @Min(0) private long backoffMs = 250;
        @Min(1) private long maxBackoffMs = 5000;
        @DecimalMin("0.0") @DecimalMax("1.0") private double jitter = 0.5;
        /** Shared extra-attempt budget per process, not a quota per caller. */
        @Min(0) @Max(10000) private int budgetPerMinute = 30;
        /** Compatibility for callers using the original boolean jitter flag. */
        public boolean isJitter() { return jitter > 0; }
    }

    /**
     * 熔断（ADR-32）：判定依据是<b>滑窗失败率</b>，不是连续失败次数。
     * 旧的 {@code failure-threshold}（连续 N 次即开）已删除——它会把厂商个位数百分比的
     * 背景拒绝经重试放大成 15s 全量降级。
     */
    @Data
    public static class Circuit {
        private boolean enabled = true;
        /** 打开时长基准，也是半开探针的最短等待。 */
        @Min(1) private long openMs = 15000;
        /** 打开时长退避上限（每次半开探针失败翻倍，直到本值）。 */
        @Min(1) private long maxOpenMs = 60000;
        /** 滑窗容量：最近 N 次调用参与失败率计算。 */
        @Min(1) @Max(1000) private int slidingWindowSize = 50;
        /** 样本不足时不判定（避免冷启动/低流量误开）。 */
        @Min(1) @Max(1000) private int minimumNumberOfCalls = 20;
        /** 失败率阈值，达到即打开。 */
        @DecimalMin("0.0") @DecimalMax("1.0") private double failureRateThreshold = 0.5;
        /** 半开状态允许的并发探针数（>1 可显著加快恢复）。 */
        @Min(1) @Max(64) private int halfOpenProbes = 3;
    }

    /**
     * 自适应并发（AIMD，2026-09-16 新增）。
     *
     * <p>厂商的并发/速率上限并非固定值（会随账号配额、机房、上游策略变化），
     * 因此不再把闸门硬编码在某个实测值上，而是让网关**自己探**：
     * 遇到限流就乘性收缩，持续成功就加性回升。这是 TCP 拥塞控制同源的经典做法，
     * 也是 Netflix / AWS 等对下游限流的通行处理。</p>
     */
    @Data
    public static class Adaptive {
        private boolean enabled = true;
        /** 收缩下限（不允许低于此值，避免自我饿死）。 */
        @Min(1) private int minConcurrentCalls = 4;
        /** 乘性收缩系数（遇到限流时）。 */
        @DecimalMin("0.1") @DecimalMax("1.0") private double decreaseFactor = 0.7;
        /** 连续成功多少次后加性回升 1。 */
        @Min(1) private int increaseAfterSuccesses = 20;
    }
}
