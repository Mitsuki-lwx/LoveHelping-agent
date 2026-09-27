package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.config.RestFallbackChatModel;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static cn.lwx.lwxaiagent.infrastructure.ai.LlmFailurePolicy.*;

/** ADR-23: one retry owner, finite budgets, provider circuits, and no replay after streamed output. */
@Component
public class LlmGateway implements ChatModel {
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);
    private final ChatModel primary;
    /**
     * ADR-52：降级链。容器按 {@code @Order} 注入，**注册几个就有几级**；
     * 空列表 = 不降级（ADR-51 起的当前形态）。
     * <p>ADR-48 时这里是两个具名字段（{@code fallback} / {@code lastResort}），
     * 加第 3 级要改构造器签名 —— 那是 ADR-51 §已知限制 的"口子 2"。</p>
     */
    private final List<LlmFallbackTier> tiers;
    private final LlmGatewayProperties props;
    private final MeterRegistry meters;
    private final AiTelemetry telemetry;
    private final AdaptiveConcurrencyLimiter limiter;
    private final ThreadPoolExecutor blocking;
    private final ProviderCircuit primaryCircuit;
    /** ADR-52：每级一个独立熔断器，按级别名索引（ADR-48 的三个字段的泛化）。 */
    private final Map<String, ProviderCircuit> tierCircuits;
    private final ApplicationEventPublisher events;
    /** ADR-48：只为把"生效端点"打进启动日志与指标；单测传 null（拿不到就如实显示未知）。 */
    private final org.springframework.core.env.Environment env;
    private double retryTokens;
    private long refillNanos = System.nanoTime();

    /**
     * ADR-52 降级链的唯一注入入口。**链长与目标全部由容器装配决定，网关本身不写死**：
     * 降级级是 {@link LlmFallbackTier} 类型的 bean，注册几个就有几级，按 {@code @Order} 排序。
     *
     * <p><b>为什么是 {@code ObjectProvider} 而不是 {@code List<LlmFallbackTier>}</b>：
     * 构造函数参数写成 {@code List<T>} 时，<b>零个候选 bean 会让 Spring 抛
     * {@code NoSuchBeanDefinitionException}</b>，而"零个降级级"正是 ADR-51 起的当前配置
     * —— 应用会起不来。{@code ObjectProvider#orderedStream()} 在零候选时返回空流。</p>
     *
     * <p><b>为什么必须 {@code filter(Objects::nonNull)}</b>：{@code BigModelLastResortConfig}
     * 在缺 {@code BIGMODEL_API_KEY} 时<b>返回 null</b>（Spring 记为 {@code NullBean}，
     * 这是刻意的：最低等级的兜底端点缺凭据不该阻断启动），流里可能解出 null。</p>
     *
     * <p><b>ADR-51（2026-09-27）起当前生效形态</b>：
     * primary = DeepSeek 官方 {@code https://api.deepseek.com} + {@code deepseek-flash}，
     * <b>两个降级级均未注册</b>，故 {@code tiers} 为空列表，行为退化为「主链 + 重试」。</p>
     *
     * <p><b>历史上这一级曾是什么</b>（改代码时别照旧注释理解）：
     * primary 曾为 OpenRouter {@code stealth/space-bunny-alpha}（更早是 qwen-plus）；
     * 降级级曾为 DashScope {@code qwen-plus}（域名本机不可达，属"假备用"）
     * 与 bigmodel {@code glm-4-flash}（实测 400）。</p>
     *
     * <p>不变量（ADR-23 / ADR-31）在多级链下**不变**：
     * 网关仍是唯一重试所有者；每一级有独立熔断器；并发许可覆盖整条链；
     * 流式一旦 emit 就不重放。业务层对链长完全无感知。</p>
     *
     * <p>⛔ 只能有这一个 {@code @Autowired} 构造器 —— 曾并存两个，Spring 直接拒绝启动
     * （{@code Invalid autowire-marked constructor}），而单测因为不走 Spring 容器<b>全绿</b>。
     * 这又一次印证："单测全绿"证明不了 Spring 装配正确，<b>必须真启动一次</b>。</p>
     */
    @Autowired
    public LlmGateway(@Qualifier("openAiChatModel") ChatModel primary,
                      ObjectProvider<LlmFallbackTier> tiers,
                      LlmGatewayProperties props, MeterRegistry meters, AiTelemetry telemetry,
                      ApplicationEventPublisher events, org.springframework.core.env.Environment env) {
        this(primary, tiers.orderedStream().filter(Objects::nonNull).toList(),
                props, meters, telemetry, events, env);
    }

    /**
     * 核心构造器（包私有，供单测构造**任意级数**的链 —— 这是 ADR-52 G2 的验收手段）。
     */
    LlmGateway(ChatModel primary, List<LlmFallbackTier> tiers,
               LlmGatewayProperties props, MeterRegistry meters, AiTelemetry telemetry,
               ApplicationEventPublisher events, org.springframework.core.env.Environment env) {
        this.primary = primary;
        this.tiers = List.copyOf(tiers);
        this.props = props;
        this.meters = meters;
        this.telemetry = telemetry;
        this.events = events;
        this.env = env;
        int max = Math.max(1, props.getMaxConcurrentCalls());
        this.limiter = new AdaptiveConcurrencyLimiter(max, props.getAdaptive());
        this.blocking = new ThreadPoolExecutor(max, max, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("llm-call-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        blocking.allowCoreThreadTimeOut(true);
        var c = props.getCircuit();
        primaryCircuit = new ProviderCircuit(c);
        // ADR-52：每级一个独立熔断器（与 ADR-48 语义一致，只是从 3 个字段变成按名字建表）。
        // 重名直接启动失败 —— 否则两级会**静默共用一个熔断器**，比不建更坏。
        Map<String, ProviderCircuit> circuits = new LinkedHashMap<>();
        for (LlmFallbackTier tier : this.tiers) {
            if (circuits.putIfAbsent(tier.name(), new ProviderCircuit(c)) != null) {
                throw new IllegalStateException("降级级名字重复：" + tier.name()
                        + " —— 两级共用一个熔断器会让降级语义不可解释，请改 @Bean 名");
            }
        }
        this.tierCircuits = Collections.unmodifiableMap(circuits);
        retryTokens = props.getRetry().getBudgetPerMinute();
        meters.gauge("llm.inflight", limiter, AdaptiveConcurrencyLimiter::inflight);
        // ADR-32: 闸门不再是固定值，暴露自适应收敛结果便于观测"厂商现在能容忍多少"。
        meters.gauge("llm.permits.limit", limiter, AdaptiveConcurrencyLimiter::limit);
        publishEffectiveEndpoints();
    }

    /**
     * ADR-48 取证缺口：把"这一级实际连的是哪个端点 + 哪个模型"变成**可查询的指标**。
     *
     * <p><b>为什么必须补</b>：22 项 E2E 全绿 + "402 出现 0 次"只证明**没报错**，
     * 不证明**打对了地方**。实测发现应用启动日志里 {@code model=} 的所有出现
     * （共 6 处）**全部来自 rerank / embedding / bigmodel 兜底注册**，
     * 主 LLM 端点<b>在日志里一条痕迹都没有</b> —— 于是"生效端点是什么"只能靠推断，
     * 而"我 export 了"早已被证明不等于"它生效了"（profile yml 字面值会覆盖环境变量）。
     * 一旦主端点被静默换掉，现有自动化<b>不会有一条变红</b>。</p>
     *
     * <p><b>形态选择</b>：用带 tag 的 counter（值恒为 1）而不是 log。
     * log 只在启动时打一次、且 grep 容易漏（这正是本次踩的坑）；
     * 指标可被任何时刻的 E2E / 巡检脚本读到，且**换端点后旧值会消失**，
     * 天然构成"漂移可见"。</p>
     */
    private void publishEffectiveEndpoints() {
        // ⭐ primary 也要报：它是**最该被观测**的一级（换端点时最容易静默漂移的那级），
        //    但它不在 degradeTiers() 里（那个列表只含降级目标）—— 漏掉它正是本方法第一版的错。
        report("primary", primary);
        for (Tier tier : degradeTiers()) report(tier.name(), tier.model());
        log.info("[ADR-52] 网关生效 timeout：attempt={}ms firstByte={}ms total={}ms streamIdle={}ms "
                        + "degradeEnabled={} 降级链=[{}]",
                props.getAttemptTimeoutMs(), props.getFirstByteTimeoutMs(), props.getTotalTimeoutMs(),
                props.getStreamIdleTimeoutMs(), props.isDegradeEnabled(),
                tiers.isEmpty() ? "空（单级：主链 + 重试）"
                        : tiers.stream().map(LlmFallbackTier::name).reduce((a, b) -> a + " → " + b).orElse(""));
    }

    private void report(String level, ChatModel model) {
        String where = endpointOf(level, model);
        // counter 恒为 1：值本身无意义，有意义的是"这个 tag 组合此刻存在"。
        // 换端点后旧 tag 会消失 → 巡检脚本能直接看出漂移。
        meters.counter("llm.endpoint.configured", "level", level, "target", where).increment();
        log.info("[ADR-48] 降级链 {} 级实际生效端点：{}", level, where);
    }

    /**
     * 尽力取出这一级的**端点 + 模型名**；取不到就如实写"未知"，<b>绝不编造</b>
     * —— 假端点比没端点更坏（它会让人以为已经观测到了）。
     *
     * <p>base-url 只能从 Spring 配置里取（{@code spring.ai.openai.*}），
     * ChatModel 实例本身不暴露它；模型名则从默认 options 取。</p>
     */
    private String endpointOf(String level, ChatModel model) {
        String name = "?";
        try {
            if (model instanceof org.springframework.ai.openai.OpenAiChatModel m
                    && m.getDefaultOptions() != null) {
                name = String.valueOf(m.getDefaultOptions().getModel());
            } else if (model instanceof RestFallbackChatModel) {
                name = "dashscope-native"; // 自建 RestClient，模型名固定 qwen-plus
            } else {
                name = model.getClass().getSimpleName();
            }
        } catch (RuntimeException e) {
            name = "?(" + e.getClass().getSimpleName() + ")";
        }
        String base = endpointBaseUrl(level);
        return base.isEmpty() ? name : base + " | " + name;
    }

    /**
     * 各级的 base-url 取法不同，故分两路（ADR-52 起不再按 {@code "fallback"} /
     * {@code "last-resort"} 这种<b>字面量</b>分支 —— 那是 ADR-51 §已知限制 的口子 3）：
     * <ul>
     *   <li>{@code primary} → {@code spring.ai.openai.base-url}（Spring AI 自动配置的端点）</li>
     *   <li>降级级 → 取该 {@link LlmFallbackTier#baseUrl()}（由产出它的配置类填，
     *       因为这些级多为自建 client，Spring 属性里查不到）</li>
     * </ul>
     * ⛔ 取不到就<b>不写 base</b>，绝不回退到主端点的 URL —— 第一版就犯过这个错，
     * 把兜底级显示成 {@code "openrouter.ai | glm-4-flash"}，<b>比不显示更有害</b>
     * （它会让人以为已经观测到了）。
     */
    private String endpointBaseUrl(String level) {
        if ("primary".equals(level)) {
            if (env == null) return "";
            try {
                String v = env.getProperty("spring.ai.openai.base-url", "");
                return v == null ? "" : v;
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        for (LlmFallbackTier tier : tiers) {
            if (tier.name().equals(level)) return tier.baseUrl();
        }
        return "";
    }

    /**
     * 单级降级链 + NOOP tracing，专供隔离单测。
     *
     * <p>⛔ 刻意保留四参形态：{@code LlmGatewayTest} 的 27 个用例全部走它，
     * 删掉会把它们全变红，而它们证明的是"**主链 + 单级降级**没被改坏"。
     * 多级链的新行为由 {@code LlmGatewayThreeTierTest} / {@code LlmGatewayTierListTest}
     * 走包私有构造器单独覆盖（ADR-48 / ADR-52）。</p>
     *
     * @param fallback 降级目标；传 {@code null} 表示无降级级（等价于空链）
     */
    public LlmGateway(ChatModel primary, ChatModel fallback, LlmGatewayProperties props, MeterRegistry meters) {
        this(primary,
                fallback == null ? List.<LlmFallbackTier>of()
                        : List.of(new LlmFallbackTier("fallback", fallback)),
                props, meters, new AiTelemetry(Tracer.NOOP), event -> { }, null);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getTotalTimeoutMs());
        TraceContext parent = telemetry.capture();
        // ADR-31 发现二：并发许可覆盖"重试 + 降级"整条链路，而不是每次尝试各借还一次。
        // 原来的写法会在两次尝试之间留下准入空窗——凭证被放回池中可能立即易主，
        // 而厂商侧那次调用未必已经结束，于是瞬时在途会突破收敛后的容量口径；
        // 本次请求自己的重试也可能因别人取走凭证而被自家 4003 拒掉。
        // 必须经 publicFailure 映射：调用方（如 SkillReflector）靠 BizException(4003) 识别
        // "自家闸门满"这一预期结果；裸 CapacityException 会被它当成真故障打 ERROR 全栈（回归）。
        if (!limiter.tryAcquire()) throw publicFailure(new CapacityException());
        try {
            return callWithRetries(prompt, deadline, parent);
        } finally {
            limiter.release();
        }
    }

    private ChatResponse callWithRetries(Prompt prompt, long deadline, TraceContext parent) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= props.getRetry().getMaxAttempts(); attempt++) {
            try {
                return syncAttempt(primary, primaryCircuit, prompt, "primary", attempt, deadline, parent);
            } catch (RuntimeException e) {
                failure = e;
                if (cancelled(e)) throw cancellation();
                long delay = delayMs(e, attempt, props.getRetry(), System.currentTimeMillis());
                if (!retryable(e) || !canRetry(attempt, delay, deadline)) break;
                metric("llm.retry", "primary", "scheduled");
                try { Thread.sleep(delay); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw cancellation(); }
            }
        }
        // ADR-48：降级链按声明顺序走完。每一级只尝试一次——降级不是重试，
        // 不该被 max-attempts 放大（沿用旧 fallback 语义）。
        // ADR-52：链由容器装配决定，空链时本循环不执行 → 行为退化为「主链 + 重试」。
        for (Tier tier : degradeTiers()) {
            if (!canDegradeTo(tier, failure) || remainingMs(deadline) <= 0) continue;
            metric("llm.fallback", tier.name, "started");
            try {
                return syncAttempt(tier.model, tier.circuit, prompt, tier.name, 1, deadline, parent);
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        throw publicFailure(failure);
    }

    /**
     * ADR-52：降级链 = 容器里所有 {@link LlmFallbackTier} bean，按 {@code @Order} 升序。
     * <b>注册几个就有几级</b>；空列表 = 无降级（ADR-51 起的当前形态）。
     */
    private List<Tier> degradeTiers() {
        List<Tier> out = new ArrayList<>(tiers.size());
        for (LlmFallbackTier tier : tiers) {
            out.add(new Tier(tier.model(), tierCircuits.get(tier.name()), tier.name()));
        }
        return out;
    }

    /** ADR-48：判"能不能降级"看**总闸**；ADR-52 起总闸是 {@code app.llm.degrade-enabled}。 */
    private boolean canDegradeTo(Tier tier, RuntimeException failure) {
        return props.isDegradeEnabled() && failure != null && fallbackAllowed(failure);
    }

    /** 一级降级目标：模型 + 独立熔断器 + 指标标签名。 */
    private record Tier(ChatModel model, ProviderCircuit circuit, String name) { }

    private ChatResponse syncAttempt(ChatModel model, ProviderCircuit circuit, Prompt prompt, String provider,
                                     int attempt, long deadline, TraceContext parent) {
        if (Thread.currentThread().isInterrupted()) throw cancellation();
        long timeout = Math.min(props.getAttemptTimeoutMs(), remainingMs(deadline));
        if (timeout <= 0) throw new CompletionException(new TimeoutException());
        ProviderCircuit.Ticket ticket = circuit.acquire();
        if (ticket == null) { metric("llm.circuit", provider, "rejected"); throw new CircuitOpenException(); }
        Span span = attemptSpan(provider, attempt, parent);
        long start = System.nanoTime();
        Future<ChatResponse> work = null;
        String outcome = "fail";
        try {
            work = blocking.submit(() -> {
                // 并发许可由 call() 在整个链路外层统一持有（ADR-31 发现二），worker 内不再借还。
                try (var ignored = telemetry.scope(span)) {
                    ChatResponse response = model.call(prompt);
                    if (!meaningful(response)) throw new EmptyResponseException();
                    return response;
                }
            });
            ChatResponse response = work.get(timeout, TimeUnit.MILLISECONDS);
            ticket.success();
            if (limiter.onSuccess()) adaptive(provider, "grow");
            // 同步路径的"首字节"= 整个响应返回。**同步请求不流式，用户看到的就是这段等待**，
            // 而 phase16 查出的白屏问题在这里同样存在（只是发生在工作线程里，客户端更看不见）。
            recordFirstToken(provider, attempt, (System.nanoTime() - start) / 1_000_000);
            usage(response, provider, span);
            outcome = "success";
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ticket.cancel();
            outcome = "cancelled";
            throw cancellation();
        } catch (TimeoutException e) {
            ticket.failure();
            outcome = "timeout";
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            RuntimeException failure = cause instanceof RuntimeException r ? r : new CompletionException(cause);
            if (fallbackAllowed(failure)) ticket.failure(); else ticket.cancel();
            if (throttled(failure) && limiter.onThrottled()) adaptive(provider, "shrink");
            outcome = cancelled(failure) ? "cancelled" : "fail";
            throw failure;
        } catch (RejectedExecutionException e) {
            ticket.cancel();
            outcome = "rejected";
            throw new CapacityException();
        } finally {
            if (work != null && !work.isDone()) work.cancel(true);
            // The worker (not Future completion) releases its permit, even for an uninterruptible SDK.
            ticket.cancel();
            finish(span, provider, outcome, start);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        TraceContext captured = telemetry.capture();
        return Flux.deferContextual(context -> {
            // ADR-31 发现二：并发许可在订阅入口获取一次，覆盖该请求的全部重试与降级尝试，
            // 直到整条流终止（complete / error / cancel）才归还。此前每次尝试各借还一次，
            // 尝试之间的空窗会让凭证易主，厂商侧瞬时在途因此可突破容量上限。
            if (!limiter.tryAcquire()) return Flux.error(publicFailure(new CapacityException()));
            AtomicBoolean permitReleased = new AtomicBoolean();
            Runnable releasePermit = () -> { if (permitReleased.compareAndSet(false, true)) limiter.release(); };
            TraceContext parent = context.getOrDefault(AiTelemetry.PARENT_CONTEXT_KEY, captured);
            AtomicBoolean emitted = new AtomicBoolean();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getTotalTimeoutMs());
            return degradingStream(prompt, emitted, deadline, parent, degradeTiers(), 0)
                    .takeUntilOther(Mono.delay(Duration.ofMillis(props.getTotalTimeoutMs()))
                            .flatMap(t -> {
                                // ⭐ 这里**只管 total-timeout（整条流的硬上限，90s）**。
                                //   流式还有另外两种超时发生在 streamAttempt 里（首字节 45s / 空窗 15s），
                                //   日志与指标打在那边 —— 2026-09-27 踩过：只加这里，
                                //   E2E 打出 outcome=timeout=2 却 grep 不到日志，排查绕了一圈。
                                //   三种 timeout 必须分开打，处置完全不同：
                                //     total(90s) = 整条流超上限；FIRST_BYTE(15s) = 模型没起步（上游慢/排队）；
                                //     STREAM_IDLE(15s) = 起步后中途卡住（内容长/工具调用/思考）。
                                // emitted 单独打：已吐内容与未吐内容是两种故障（后者才可能降级重试），
                                // 混在一起就分不清是"上游慢"还是"下游不消费"。
                                log.warn("[ADR-48] LLM 流式触发 total-timeout={}ms（emitted={}）→ 直接失败，"
                                                + "不降级（流已开始或无可降级目标）",
                                        props.getTotalTimeoutMs(), emitted.get());
                                metric("llm.timeout.total", "stream", emitted.get() ? "emitted" : "not_emitted");
                                return Mono.error(new TimeoutException("LLM total deadline exceeded"));
                            }))
                    .onErrorMap(this::publicFailure)
                    .doFinally(signal -> releasePermit.run());
        });
    }

    private Flux<ChatResponse> primaryStream(Prompt prompt, int attempt, AtomicBoolean emitted,
                                            long deadline, TraceContext parent) {
        return streamAttempt(primary, primaryCircuit, prompt, "primary", attempt, emitted, parent)
                .onErrorResume(e -> {
                    long delay = delayMs(e, attempt, props.getRetry(), System.currentTimeMillis());
                    if (!emitted.get() && retryable(e) && canRetry(attempt, delay, deadline)) {
                        metric("llm.retry", "primary", "scheduled");
                        return Mono.delay(Duration.ofMillis(delay))
                                .thenMany(primaryStream(prompt, attempt + 1, emitted, deadline, parent));
                    }
                    return Flux.error(e);
                });
    }

    /**
     * ADR-48：按链走流式尝试。{@code tierIndex}=0 是 primary（带重试），
     * &gt;0 是各级降级目标（每级只试一次，不重试——与 call 侧语义一致）。
     *
     * <p>「已 emit 就不重放」这条不变式在每一级都重新检查：{@code emitted} 是链级共享的，
     * 所以任何一级吐过内容，后面所有降级都不会再发生。</p>
     */
    private Flux<ChatResponse> degradingStream(Prompt prompt, AtomicBoolean emitted, long deadline,
                                               TraceContext parent, List<Tier> tiers, int tierIndex) {
        Flux<ChatResponse> current = tierIndex == 0
                ? primaryStream(prompt, 1, emitted, deadline, parent)
                : streamAttempt(tiers.get(tierIndex - 1).model(), tiers.get(tierIndex - 1).circuit(),
                        prompt, tiers.get(tierIndex - 1).name(), 1, emitted, parent);
        return current.onErrorResume(e -> {
            if (emitted.get() || !canFallback(e) || remainingMs(deadline) <= 0) return Flux.error(e);
            if (tierIndex >= tiers.size()) return Flux.error(e);
            metric("llm.fallback", tiers.get(tierIndex).name(), "started");
            return degradingStream(prompt, emitted, deadline, parent, tiers, tierIndex + 1);
        });
    }

    private Flux<ChatResponse> streamAttempt(ChatModel model, ProviderCircuit circuit, Prompt prompt,
                                            String provider, int attempt, AtomicBoolean emitted, TraceContext parent) {
        return Flux.defer(() -> {
            ProviderCircuit.Ticket ticket = circuit.acquire();
            if (ticket == null) { metric("llm.circuit", provider, "rejected"); return Flux.error(new CircuitOpenException()); }
            // 并发许可不在此处借还：由 stream() 在整条链路外层统一持有（ADR-31 发现二）。
            Span span = attemptSpan(provider, attempt, parent);
            long start = System.nanoTime();
            AtomicBoolean content = new AtomicBoolean();
            // 「是否已记录过 TTFT」—— 名字里必须带 Recorded，否则会和下面超时判断里的
            // 「是否首字节超时」混成同一个概念（两者恰好在首字节前后相反）。
            AtomicBoolean ttftRecorded = new AtomicBoolean();
            AtomicReference<ChatResponse> lastUsage = new AtomicReference<>();
            AtomicReference<String> outcome = new AtomicReference<>("cancelled");
            return Flux.defer(() -> {
                        try (var ignored = telemetry.scope(span)) { return model.stream(prompt); }
                    })
                    // ADR-49：首值用 first-byte-timeout（15s），**不再**复用 attempt-timeout。
                    //   attempt-timeout 是同步整调用的预算（生成 300~500 tok 实测 10~17s）；
                    //   拿它当首字节上限，真故障时用户要白等 45s 才进降级。
                    //   实测 TTFT 43 例 max=4.95s（scripts/probe_first_token_latency.py）。
                    .timeout(Mono.delay(Duration.ofMillis(props.getFirstByteTimeoutMs())),
                            r -> Mono.delay(Duration.ofMillis(props.getStreamIdleTimeoutMs())))
                    .doOnNext(r -> {
                        // ⭐ 首字节延迟埋点（phase17）：**只记第一个** chunk。
                        // phase16 只能看到「超时了」，看不到「正常要多久」→ 调 timeout 没有依据，
                        // 只能拍脑袋。这是补测量，不是补日志。
                        // 用 summary + max：p50/p90/p99 由 prometheus 侧算，
                        // 严禁用 gauge（phase16 教训：分母/取值逐次变化的瞬时值会被误读）。
                        if (ttftRecorded.compareAndSet(false, true)) {
                            recordFirstToken(provider, attempt, (System.nanoTime() - start) / 1_000_000);
                        }
                        emitted.set(true); // Any emitted response commits the stream; never replay prefixes/tool calls.
                        if (meaningful(r)) content.set(true);
                        if (hasUsage(r)) lastUsage.set(r);
                    })
                    .concatWith(Flux.defer(() -> content.get() ? Flux.empty() : Flux.error(new EmptyResponseException())))
                    .doOnComplete(() -> {
                        ticket.success();
                        outcome.set("success");
                        if (limiter.onSuccess()) adaptive(provider, "grow");
                    })
                    .doOnError(e -> {
                        if (fallbackAllowed(e)) ticket.failure(); else ticket.cancel();
                        if (throttled(e) && limiter.onThrottled()) adaptive(provider, "shrink");
                        outcome.set(e instanceof TimeoutException ? "timeout" : "fail");
                        // ⭐ 流式超时的**真实发生地**在这里（`.timeout(首值, 空值)`，
                        //   见本方法上游），**不在** takeUntilOther 的 total-timeout 那条路。
                        //   实测教训：2026-09-27 我先把日志加在 total-timeout 上，
                        //   结果 E2E 打出 outcome=timeout=2 却 grep 不到任何日志 —— 覆盖错了地方。
                        //   两种 timeout 必须分开打：首字节超时 = 模型根本没起步（上游慢/排队）；
                        //   空窗超时 = 起步了但中途卡住（内容长、工具调用、思考）。处置完全不同。
                        if (e instanceof TimeoutException) {
                            // 注意与上面的 ttftRecorded 区分：那是「已记录过 TTFT」，
                            // 这里判的是「超时时**一个字节都还没吐**」→ 才是首字节超时。
                            boolean isFirstByteTimeout = !emitted.get();
                            log.warn("[ADR-48] LLM 流式超时 provider={} attempt={} kind={} "
                                            + "firstByteTimeoutMs={} streamIdleTimeoutMs={} emittedAny={} contentAny={}",
                                    provider, attempt, isFirstByteTimeout ? "FIRST_BYTE" : "STREAM_IDLE",
                                    props.getFirstByteTimeoutMs(), props.getStreamIdleTimeoutMs(),
                                    emitted.get(), content.get());
                            metric("llm.timeout.stream", provider,
                                    isFirstByteTimeout ? "first_byte" : "stream_idle");
                        }
                    })
                    .doFinally(signal -> {
                        ticket.cancel();
                        usage(lastUsage.get(), provider, span); // Cumulative usage is recorded ONCE, not per chunk.
                        finish(span, provider, outcome.get(), start);
                    });
        });
    }

    /**
     * 首字节（TTFT）延迟埋点 —— 流式与同步<b>共用一份实现</b>。
     *
     * <p><b>为什么要它</b>：phase16 只能看到「超时了」，<b>看不到「正常要多久」</b> →
     * 调 {@code attempt-timeout} 没有任何依据，只能拍脑袋。补这个埋点是为了让
     * 「把 timeout 定成 p99 的 1.5~2 倍」这句话<b>可执行</b>。</p>
     *
     * <p>⛔ <b>不用 gauge</b>：TTFT 每次都不同，瞬时值会被后来者读成「稳定首字节延迟」
     * （phase16 已在命中率上栽过同一类坑）。用 {@link io.micrometer.core.instrument.Timer}
     * + percentiles，让 prometheus 侧自己算分位。</p>
     */
    private void recordFirstToken(String provider, int attempt, long ms) {
        try {
            io.micrometer.core.instrument.Timer.builder("llm.time_to_first_token")
                    .tag("provider", provider)
                    .tag("attempt", String.valueOf(attempt))
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(meters)
                    .record(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // 埋点绝不能影响主链路
        }
    }

    private boolean canRetry(int failedAttempt, long delay, long deadline) {
        return failedAttempt < props.getRetry().getMaxAttempts()
                && delay <= props.getRetry().getMaxBackoffMs() && delay < remainingMs(deadline)
                && takeRetryToken();
    }

    private synchronized boolean takeRetryToken() {
        long now = System.nanoTime();
        int capacity = props.getRetry().getBudgetPerMinute();
        retryTokens = Math.min(capacity, retryTokens + (now - refillNanos) / 60_000_000_000.0 * capacity);
        refillNanos = now;
        if (retryTokens < 1) { metric("llm.retry", "primary", "budget_rejected"); return false; }
        retryTokens--;
        return true;
    }

    /**
     * ADR-48：判"能不能降级"只看**降级链非空** —— 不能只看某一级，链上任意一级可用即可。
     * ADR-52：链 = 容器里注册的 tier 列表；总闸是 {@code app.llm.degrade-enabled}。
     */
    private boolean canFallback(Throwable e) {
        return props.isDegradeEnabled() && !tiers.isEmpty()
                && e != null && fallbackAllowed(e);
    }

    /**
     * 闸门收敛值发生变化时：记指标 + 广播给准入层（ADR-32）。
     *
     * <p>准入层必须跟随同一个收敛值，否则"网关放行 24、实际只跑 15"会让多出的请求
     * 变成自家 4003——厂商的 429 只是被换成自家拒绝，用户可见失败率反而升高。</p>
     */
    private void adaptive(String provider, String outcome) {
        metric("llm.adaptive", provider, outcome);
        events.publishEvent(new CapacityLimitChanged(limiter.limit()));
    }

    private RuntimeException publicFailure(Throwable e) {
        if (cancelled(e)) return cancellation();
        if (e instanceof BizException b) return b;
        if (e instanceof CapacityException) return new BizException(4003, "AI 服务繁忙，请稍后再试", java.util.Map.of("retryAfterSec", 2));
        return new BizException(5000, "AI 服务暂时不可用，请稍后再试");
    }

    private static CancellationException cancellation() { return new CancellationException("LLM request cancelled"); }
    private long remainingMs(long deadline) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
    private boolean meaningful(ChatResponse r) {
        if (r == null || r.getResult() == null || r.getResult().getOutput() == null) return false;
        var output = r.getResult().getOutput();
        return (output.getText() != null && !output.getText().isBlank())
                || (output.getToolCalls() != null && !output.getToolCalls().isEmpty());
    }

    private boolean hasUsage(ChatResponse r) { return r != null && r.getMetadata() != null && r.getMetadata().getUsage() != null; }
    private void usage(ChatResponse r, String provider, Span span) {
        if (!hasUsage(r)) return;
        var usage = r.getMetadata().getUsage();
        int input = usage.getPromptTokens() == null ? 0 : Math.max(0, usage.getPromptTokens());
        int output = usage.getCompletionTokens() == null ? 0 : Math.max(0, usage.getCompletionTokens());
        meters.counter("llm.tokens", "provider", provider, "type", "prompt").increment(input);
        meters.counter("llm.tokens", "provider", provider, "type", "completion").increment(output);
        // cached 只在 >0 时才 increment —— 0 不代表"没命中"，可能只是取不到（见 cachedTokens 注释）。
        // 代价是"命中 0 次"和"取不到"在指标上同样表现为 counter 缺失，这是已知且被接受的模糊性。
        int cached = cachedTokens(usage);
        if (cached > 0) meters.counter("llm.tokens", "provider", provider, "type", "cached").increment(cached);
        // ⚠️ **故意不埋命中率 gauge**：比例的分母（prompt 长度）逐次变化，
        // 一个瞬时 gauge 极易被后来者当成"稳定命中率"读；且 Micrometer 的 gauge 签名
        // 要求持有对象引用，为一个派生量引入状态不划算。命中率由**量具侧**用
        // llm.tokens{type="cached"} / llm.tokens{type="prompt"} 的窗口差值自己算 —— 分母同源，更可信。
        span.tag("langfuse.observation.usage_details",
                "{\"input\":" + input + ",\"output\":" + output + ",\"cache_read\":" + cached + "}");
        if (r.getMetadata().getModel() != null) span.tag("langfuse.observation.model.name", r.getMetadata().getModel());
    }

    /**
     * 取 prompt cache 命中 token 数；取不到就返回 0（<b>不猜、不填</b>）。
     *
     * <p><b>为什么必须有这个方法</b>：Spring AI 的 {@code DefaultUsage} <b>没有</b>
     * {@code cachedTokens} 字段 → "缓存命中"在生产链路上<b>完全不可观测</b>。
     * 而缓存恰是 ADR-48 换端点之后唯一还需要持续盯的收益项
     * （bigmodel 端点 usage 里根本没有 cache 字段，space-bunny 有）。
     * 缺了它，命中率掉到 0 <b>不会有一条自动化变红</b> ——
     * 与 ADR-48「生效端点零痕迹」是同一类盲区。</p>
     *
     * <p><b>取值路径</b>：{@code Usage#getNativeUsage()} 装的是上游原始 usage 对象
     * （OpenAI 适配器是 {@code OpenAiApi.Usage}，其 {@code promptTokensDetails().cachedTokens}
     * 即命中数）。按"反射 + 单一形状"取，而非强转具体类型 ——
     * 换 provider/换适配器时不该编译失败。</p>
     *
     * ⛔ <b>0 的含义是"取不到"或"确实没命中"，二者目前不可区分</b>。
     * 区分需要改适配器（列入待办）。**报告里不要把 0 说成"没命中"** ——
     * 这正是"字段存在 ≠ 缓存可用"那条纪律的翻版：字段取到 ≠ 值有意义。</p>
     */
    private int cachedTokens(org.springframework.ai.chat.metadata.Usage usage) {
        try {
            Object native_ = usage.getNativeUsage();
            if (native_ == null) return 0;
            Object details = native_.getClass().getMethod("promptTokensDetails").invoke(native_);
            if (details == null) return 0;
            Object v = details.getClass().getMethod("cachedTokens").invoke(details);
            return v instanceof Integer i ? Math.max(0, i) : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 上游没这个字段（部分 provider 不返回 cached_tokens）→ 如实当作取不到
            return 0;
        }
    }

    private Span attemptSpan(String provider, int attempt, TraceContext parent) {
        Span span = telemetry.start("llm.attempt", parent);
        span.tag("langfuse.observation.type", "generation");
        span.tag("llm.provider", provider);
        span.tag("llm.attempt", String.valueOf(attempt));
        return span;
    }
    private void finish(Span span, String provider, String outcome, long start) {
        try {
            span.tag("llm.outcome", outcome);
            if (!"success".equals(outcome) && !"cancelled".equals(outcome)) telemetry.failure(span, outcome);
            metric("llm.call", provider, outcome);
            meters.timer("llm.latency", "provider", provider, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        } finally { span.end(); }
    }
    private void metric(String name, String provider, String outcome) {
        meters.counter(name, "provider", provider, "outcome", outcome).increment();
    }
    @Override public ChatOptions getDefaultOptions() { return primary.getDefaultOptions(); }
    @PreDestroy public void close() { blocking.shutdownNow(); }
}
