# spec — ADR-31 收尾：准入完备性（发现二 / 发现三）

**状态**：待实施
**关联 ADR**：ADR-23、ADR-29、ADR-31（发现二·三）、ADR-32、ADR-33

---

## 1. 现状（逐行核对，2026-09-17）

### 1.1 已成立的准入不变式

`ChatModelConfig:38` 声明 `@Primary ChatModel primaryChatModel(LlmGateway)`，因此**注入裸 `ChatModel` 的消费者都拿到网关**：

| 消费者 | 注入方式 | 是否经网关 |
| --- | --- | --- |
| `ChatExecutor` / `MemoryExtractor` / `AgentLlmNode` / `QueryRewriter` / `MyKeywordEnricher` | `ChatModel`（无 `@Qualifier`） | ✅ |

### 1.2 破坏不变式的两处

| 位置 | 代码 | 后果 |
| --- | --- | --- |
| `evolution/config/EvolutionConfig.java:107` | `@Qualifier("openAiChatModel") ChatModel chatModel` → `new SkillReflector(chatModel, ...)` | 绕过 permit / 熔断 / 重试预算 / 用量归因 |
| `service/InsightService.java:38` | `@Qualifier("openAiChatModel") ChatModel chatModel` | 同上 |

两者都是低频任务，**不是** soak 中 25% vs 直连 3.4% 差距的量级解释项；但破坏"单一准入点"这条不变式，并让 `llm.usage.owner=gateway` 存在盲区。

### 1.3 准入空窗（发现二）

`LlmGateway.streamAttempt`（改造前）：

```
circuit.acquire()  →  limiter.tryAcquire()  →  model.stream()
   doOnError  : ticket.failure/cancel(); release.run()   ← "Release before downstream retry/fallback subscribes"
   doOnComplete: release.run()
   doFinally   : release.run()
```

`onErrorResume`（在 `primaryStream` 与 `stream()` 两层）随后订阅下一个 attempt → 重新 `tryAcquire()`。

**空窗的性质**：`release()` 与下一次 `tryAcquire()` 之间，permit 被放回池中，可能被**其他请求**取走。此时：

1. 本次请求的重试/降级可能因拿不到 permit 而**被自家拒绝**（`CapacityException` → 4003）；
2. 更本质的是**记账错误**：permit 代表"厂商侧在途占用"，而上游连接在 release 之后可能尚未真正关闭，因此"释放—重取"之间厂商侧瞬时在途可以突破 `max-concurrent-calls`。

ADR-31 的表述是"重试与降级请求不受 `max-concurrent-calls` 约束"。更精确的说法是：**permit 的持有区间被切成了多段，段与段之间存在空窗，且凭证可能易主**。

同步路径 `call()` 的 `syncAttempt` 有同样结构（worker 内 `tryAcquire()` + `finally release()`，每次尝试各一次）。

### 1.4 一个连带事实

`SkillReflector.reflect` 被一个宽 `catch (Exception e)` 包住，末尾 `log.error("Failed to reflect session {}: {}", chatId, e.getMessage(), e)` —— **打全栈**。走网关后"自家容量满"会成为预期结果，若仍打 ERROR 全栈，就是 ADR-33 刚治过的噪音模式在后台任务上重演。

---

## 2. 方案

### 2.1 发现三：两处改为走网关

把 `@Qualifier("openAiChatModel")` 去掉，注入 `@Primary ChatModel`：

```java
// EvolutionConfig
@Bean
public SkillReflector skillReflector(ChatModel chatModel, EvolutionProperties props) {
    return new SkillReflector(chatModel, props.getQualityThreshold());
}

// InsightService
public InsightService(ChatModel chatModel, VisionPort visionPort, ...) { ... }
```

- `SkillReflector` 内部 `ChatClient.builder(chatModel).build()` 不变：`ChatClient` 最终调 `ChatModel.call(Prompt)`，入口即网关。
- 装配安全性：容器内 `ChatModel` 候选为 `openAiChatModel` / `deepSeekChatModel` / `primaryChatModel`，其中 `primaryChatModel` 是唯一 `@Primary` → 无歧义。
- 循环依赖检查：`LlmGateway` 仅依赖 `openAiChatModel`(primary) / `deepSeekChatModel`(fallback) / props / meters / telemetry / events，**不依赖** `SkillReflector` 或 `InsightService` → 无环。

### 2.2 发现三附带：容量拒绝不打全栈

`SkillReflector` 兜底 catch 分支化：

```java
} catch (Exception e) {
    if (capacityRejection(e)) {
        // 后台任务为用户流量让路：闸门满时跳过本轮，下一轮调度会再来（幂等）
        log.warn("Reflection for session {} deferred: LLM gateway at capacity", chatId);
    } else {
        log.error("Failed to reflect session {}: {}", chatId, e.getMessage(), e);
    }
}
```

`capacityRejection` 判定：异常链上存在 `BizException` 且 `code == 4003`（`LlmGateway.publicFailure` 对 `CapacityException` 的映射）。

### 2.3 发现二：permit 上提到"每次用户请求"

**不变式**：一次 `call()` / `stream()` 订阅 = 恰好一次 `tryAcquire()`，且该 permit 持有到**整条重试 + 降级链路**终止。

#### 同步路径

```java
@Override
public ChatResponse call(Prompt prompt) {
    long deadline = ...;
    TraceContext parent = telemetry.capture();
    if (!limiter.tryAcquire()) throw new CapacityException();   // 准入前置，省掉一次池提交
    try {
        return callWithRetries(prompt, deadline, parent);       // 原 call() 主体：重试 + 降级
    } finally {
        limiter.release();
    }
}
```

`syncAttempt` 的 worker 内移除 `limiter.tryAcquire()` / `finally release()`；`blocking` 池的 `RejectedExecutionException → CapacityException` 保留为保险（permit 在前，池实际上不会再饱和）。

#### 流式路径

```java
return Flux.deferContextual(context -> {
    if (!limiter.tryAcquire()) return Flux.error(publicFailure(new CapacityException()));
    AtomicBoolean released = new AtomicBoolean();
    Runnable releasePermit = () -> { if (released.compareAndSet(false, true)) limiter.release(); };
    ...
    return primaryStream(...)
            .onErrorResume(...)          // 降级
            .takeUntilOther(...)         // 总时限
            .onErrorMap(this::publicFailure)
            .doFinally(signal -> releasePermit.run());   // 覆盖 complete / error / cancel
});
```

`streamAttempt` 内移除 `limiter.tryAcquire()` 与三处 `release.run()`；**保留** AIMD 信号（`onSuccess` / `onThrottled`）在每个 attempt —— 它们代表"一次真实的厂商交互"，与凭证生命周期无关。

#### 语义影响

| 维度 | 改造前 | 改造后 |
| --- | --- | --- |
| permit 持有区间 | 单个 attempt | 整条请求（重试 + 降级） |
| 准入空窗 | 有（attempt 之间） | 无 |
| 一次请求的 acquire 次数 | 1..N（N = 尝试数） | 恒为 1 |
| 重试期间 permit 是否可能易主 | 是 | 否 |
| 语义 | 近似"在途 attempt 数" | 准确"在途请求数"（厂商侧占用） |

**已知代价**：重试期间不再释放 permit，其他请求在高峰期更容易被 4003 拒绝。这是**把账记准**的必然结果 —— 那些请求确实在占用厂商侧资源。需在验证阶段确认对 E2E 与并发闸门行为无功能回归。

---

## 3. 验证设计

### 3.1 单测（可区分改造前后）

**V1 · 重试空窗仍持有 permit（同步）**

配置 `maxConcurrentCalls=1`、`maxAttempts=2`、`backoffMs=300`、`jitter=0`、`fallback=null`；primary 恒抛 503。
发起 `call()`（异步线程），在 backoff 窗口内读 `llm.inflight` gauge：

- 改造前：`0`（已 release，尚未重新 acquire）
- 改造后：`1`

**V2 · 降级空窗仍持有 permit（流式）**

primary 的 `stream` 返回 `Flux.error(503)`，fallback 的 `stream` 用一个**阻塞的 Flux**（订阅后不发射）；在 fallback 被订阅后断言 `llm.inflight == 1`。

**V3 · 连续两次请求在 max=1 下串行（回归）**

确认 permit 上提没有破坏既有行为：第一次 `call()` 结束后，第二次 `call()` 必须可正常 acquire。

### 3.2 单一准入点防回归（架构守护）

**V4 · `AdmissionCompletenessTest`**

扫描 `src/main/java` 下全部 `.java` 源码，断言 `@Qualifier("openAiChatModel")` **只允许出现在 `LlmGateway.java`**（网关自身必须直连 primary 提供商模型，这是唯一合法例外）。

- 通过 = 没有消费者绕过网关；
- 失败信息给出违规文件路径 + 该行内容，便于定位。

选择源码扫描而非 Spring 上下文测试的理由：它**精确表达 ADR 的不变式**，且对将来新增的消费者自动生效（运行时测试只能覆盖已写用例的路径）。

### 3.3 运行时验证

- **E2E**：`scripts/e2e_live.py` 22 项（回归不退化）。
- **准入完备性运行时证据**：调用 `POST /insight/analyze`，确认平台/指标侧出现经网关的 `llm.attempt`（`llm.call{provider="primary"}` 计数增长），证明 `InsightService` 确实走网关。
- **闸门行为**：确认 `llm.inflight` 峰值不超过 `llm.permits.limit`（新增的"无空窗"特性最直接的观测口径）。

---

## 4. 接口与数据约定

- **无对外接口变更**。`InsightService` / `SkillReflector` 签名不变（只是注入目标改变）。
- **不变式（本次固化为契约）**：
  1. 除 `LlmGateway` 自身外，任何代码不得以 `@Qualifier("openAiChatModel")` 直连提供商模型；
  2. 一次 `call()` / `stream()` 订阅仅获取一次并发 permit，且覆盖其全部重试与降级尝试。
- 新增日志（WARN，非全栈）：`Reflection for session {} deferred: LLM gateway at capacity`。

## 5. 复现命令

```bash
# 单测
java -classpath ".../plexus-classworlds-2.9.0.jar" -Dclassworlds.conf=... ... Launcher -o test

# 真实 E2E
ADMIN_API_KEY=... python scripts/e2e_live.py --base http://127.0.0.1:<port>/api --output outputs/e2e-live-adr31.json

# 准入完备性运行时证据（InsightService 是否经网关）
curl -s -X POST "$BASE/insight/analyze" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"conversation":"[2026-01-01 10:00] 我: 你好\n[2026-01-01 10:01] 她: 嗯","sourceType":"text"}'
curl -s "$BASE/actuator/prometheus" | grep -E 'llm_call_total|llm_inflight'
```
