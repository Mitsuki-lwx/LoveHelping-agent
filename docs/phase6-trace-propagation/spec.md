# spec — 调度器路径的 trace 父上下文传播（ADR-34）

**状态**：待实施
**关联 ADR**：ADR-23、ADR-24、ADR-31（建议 3·4）、ADR-33
**挂账来源**：`docs/09` §8.13(2)(4)、`docs/phase6-adr31-followup/checklist.md` J8/J9

---

## 1. 现状（断点已静态定位，证据链见 §8.13(4)）

平台侧观测到的事实（`outputs/adr31-langfuse-probe.json`）：

| 路径 | 现象 |
| --- | --- |
| HTTP 入口（如 `POST /insight/analyze`） | ✅ trace `54df58e7…` 内含 `llm.attempt` **子 observation**（归属正确） |
| **反思任务**（`@Async`） | ❌ 任务 trace（`task reflection-scheduler.scan-and-reflect`）**只有任务 span 自己**；26 条 `llm.attempt` **零例外**自成新根（形状恒为 `{http post, chat glm-4-flash, llm.attempt}`），归属只能靠时间戳邻近推断（`07:26:35.307` → `07:26:35.383`） |

代码级断点（第 2–5 步为直接读码事实，与产物互印）：

```
@Scheduled 调度线程
  → SkillReflector.reflect(...)            @Async("evolutionExecutor")   ← 换线程
  → EvolutionConfig.evolutionExecutor()    无 TaskDecorator              ← 上下文断在此
  → 新线程 AiTelemetry.capture() = null    （tracer.currentSpan() 为 null）
  → LlmGateway.call() L78 → attemptSpan L312
  → AiTelemetry.start() 跳过 setParent      → 新根 trace
```

**对照组**：`ChatExecutor` L198–204 在请求线程 `capture()`，把 `PARENT_CONTEXT_KEY` 显式塞进
advisor 参数，`LlmGateway.stream()` 再回读 —— HTTP 路径是**显式传递**的；
而 `call()` 只有 `capture()`、没有 advisor 回退，故只在"当前线程自带 span"时正确。

**文档缺口**：`docs/03` 只规定了"检索/advisor 路径"的父上下文策略，**从未覆盖调度器路径** ——
不是实现偏离文档，而是**文档未覆盖**。按"文档即事实源"，先补 ADR-34。

---

## 2. 方案

### 2.1 候选对比

| 方案 | 形态 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **(a) 给 `evolutionExecutor` 加 `TaskDecorator`**（选用） | 在 executor 上统一装饰 | 一处生效；**将来新增的 `@Async` 后台任务自动受益**；业务代码零改动 | 影响面是该 executor 的全部任务（当前仅 1 处） |
| (b) 由 `ReflectionScheduler` 显式捕获传参 | 照抄 `ChatExecutor` 形态，把 `TraceContext` 一路送进网关 | 显式、局部 | 需改 `SkillReflector` 与网关签名；**每新增一个后台入口都要重复一遍** |

**选择 (a)**。理由是问题出在"线程边界没有传播器"这一**基础设施层**，
而不是某个调用点忘了传；把修复放在 executor 上，与 ADR-23"把不确定性关在一个地方"的口径一致。

### 2.2 实现

新增 `infrastructure/observability/TraceContextTaskDecorator`：

```java
public final class TraceContextTaskDecorator implements TaskDecorator {
    private final AiTelemetry telemetry;
    private final String spanName;

    @Override
    public Runnable decorate(Runnable task) {
        TraceContext parent = telemetry.capture();       // ← 在【提交线程】捕获
        if (parent == null) return task;                 // ← 无父上下文：零开销、零行为变化
        return () -> {
            Span span = telemetry.start(spanName, parent);   // 执行线程：建子 span
            try (var ignored = telemetry.scope(span)) {      // 设为当前 span
                task.run();
            } finally {
                span.end();
            }
        };
    }
}
```

`EvolutionConfig.evolutionExecutor()` 挂上它：

```java
executor.setTaskDecorator(new TraceContextTaskDecorator(telemetry, "task evolution.reflect"));
```

修复后的链路：

```
task reflection-scheduler.scan-and-reflect
  └── task evolution.reflect          ← 新（由装饰器建立，承载子线程上下文）
        └── llm.attempt (GENERATION)  ← 归属正确，同一 trace 内可查
```

### 2.3 语义与代价

| 维度 | 说明 |
| --- | --- |
| 无父上下文时 | 直接返回原 `Runnable`，**不建 span、不改行为**（后台任务在应用启动早期被触发时属于这种情况） |
| 有父上下文时 | 多 **1 个** span（`task evolution.reflect`），层级为 父 → 本 span → `llm.attempt` |
| 线程复用泄漏 | 用 try-with-resources 关闭作用域；span 在 `finally` 结束 |
| 是否影响业务 | 不影响。不改变任何 span 的名称/属性/层级（只**新增**一层可观测容器） |

---

## 3. 验证设计

### 3.1 单测（装饰器契约）

`TraceContextTaskDecoratorTest`，用**真实 OTel tracer**（`micrometer-tracing-bridge-otel` 已在依赖内）：

- **V1 同 traceId**：提交线程建父 span → 用装饰器包一个任务 → 在**另一线程**执行该任务 →
  任务内 `telemetry.capture()` 返回的 `traceId` == 父的 `traceId`（**改造前为 null**）。
- **V2 parent 正确**：任务内新建的 span，其 `parentId` == 父的 `spanId`（而不是自成新根）。
- **V3 无父时零副作用**：提交线程无 span 时，`decorate()` 返回**同一个** `Runnable` 实例（`assertSame`）。
- **V4 作用域不泄漏**：任务执行完后，工作线程上 `tracer.currentSpan()` 恢复为 `null`。

若 OTel tracer 构造在测试环境不可用，退化为 mock `AiTelemetry` 验证同样的调用契约
（capture 在提交线程、scope 在执行线程、span 必 end），并在产物中注明降级原因。

### 3.2 真实 E2E（回归）

`scripts/e2e_live.py` 22 项 —— 确认装饰器未破坏任何既有链路。

### 3.3 平台复验（硬性 —— 这是本项能否结案的判据）

1. 起应用（Langfuse 开）→ 调小反思触发参数（`evolution.extract-delay-seconds=30`、
   `app.scheduler.reflect.fixed-delay-ms=15000`）+ `evolution.quality-threshold=101`（不污染技能库）
2. 触发反思 → 等导出
3. 在 Langfuse 平台按**反思任务的 traceId** 查详情，断言：
   - `llm.attempt` **出现在该 trace 的 observations 内**（而不是另一条根 trace）；
   - 其 `parentId` 指向 `task evolution.reflect`（或任务 span 链上）；
   - 观察形状不再是 `{http post, chat, llm.attempt}` 的孤立根。
4. 对照：改造前同一场景下 `llm.attempt` 自成新根（已有产物 `outputs/adr31-langfuse-probe.json`）。

### 3.4 不回归项

- HTTP 入口（洞察）的 trace 结构不变（仍是 `http post /insight/analyze` → `llm.attempt`）。
- 三牌 / RAG / Agent / 沙盘等 E2E 场景全过。

---

## 4. 接口与数据约定

- 新增类：`cn.lwx.lwxaiagent.infrastructure.observability.TraceContextTaskDecorator`
  （构造：`(AiTelemetry telemetry, String spanName)`）
- 新增 span 名：`task evolution.reflect`
- **不变式（本次固化）**：**任何跨线程边界执行后台任务的地方，必须显式传播父 `TraceContext`** ——
  HTTP 入口靠 advisor 参数传播，`@Async` 路径靠 `TaskDecorator` 传播，两者都不得依赖"全局 hook"（ADR-24）。
- 无新增配置项（装饰器行为由"提交线程是否有 span"决定）。

## 5. 复现命令

```bash
# 单测
java -classpath ".../plexus-classworlds-2.9.0.jar" -Dclassworlds.conf=... ... Launcher -o test -Dtest=TraceContextTaskDecoratorTest

# 真实 E2E
ADMIN_API_KEY=... python scripts/e2e_live.py --base http://127.0.0.1:<port>/api --output outputs/e2e-live-adr34.json

# 平台复验（反思归属）
LANGFUSE_HOST=http://localhost:3000 LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=... \
  python logs/langfuse_admission_adr31.py     # 或按 traceId 直查
```
