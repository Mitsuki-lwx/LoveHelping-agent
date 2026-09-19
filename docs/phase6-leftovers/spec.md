# Phase 6 遗留收敛 —— 技术方案（spec）

> 对应 `tasks.md` 的 T1/T2/T3。每节给出**方案、为什么这么选、被否方案、判据**。

---

## S1 跨线程上下文守护

### S1.1 判据（要守住的不变式）

> 任何一个**应用自建的 `ThreadPoolTaskExecutor`**，如果它的任务可能产生 span 或读到租户身份，
> 就必须在提交时把上下文搬到执行线程；否则上下文在该线程上为 `null`。

Java 侧真正能"跨线程带上下文"的入口只有两个（ADR-24 已排除全局 hook）：
`TaskDecorator`（线程池边界）与显式参数传递（图执行走这条）。

### S1.2 方案：源码扫描（而非装配上下文）

复用 `AdmissionCompletenessTest` 的成例——**扫描源码而不是跑 Spring**：

- 扫描 `src/main/java` 全部 `.java`。
- 定位每一处 `new ThreadPoolTaskExecutor()`。
- 从该行向上找最近的 `@Bean` 方法签名，向下找方法体的收尾大括号，得到方法体文本。
- 断言方法体内出现 `setTaskDecorator(`。

**为什么不用"起容器然后反射查 Bean"**：那样只能覆盖已经写出来的池；源码扫描表达的是**不变式本身**，对将来新增的池自动生效——这正是 T1 存在的唯一理由。

### S1.3 豁免表

豁免**不是**"某些池不用管"，而是"这些池的上下文由**别处**负责"。因此豁免项必须带理由：

| 豁免 | 理由 |
| --- | --- |
| `GraphExecutorConfig.graphExecutor` | `GraphRunner` 不走线程上下文：trace 父上下文经图状态（`PIPELINE_TRACE_ID/SPAN_ID`）显式重建（`GraphRunner.pipelineSpan`），租户身份在 `execute()` 内显式 `set` / `restore`。加装饰器反而会造成"两套传播并存"的错觉。 |

豁免表以常量形式写在测试里，**新增豁免必须同时改这里和 `spec.md`**——评审可见。

### S1.4 反"恒真"断言

只断言"违规为空"是**恒真**的：扫描逻辑写坏（比如路径找错、正则不匹配）会静默全绿。
因此必须同时断言：**扫描至少命中 2 个池**（当前事实），否则测试失败并提示"扫描逻辑或目录假设有问题"。

### S1.5 反向对照实验（硬性）

守护测的是"将来"，所以必须证明它真的会拦：

```
cp EvolutionConfig.java 备份 → 注释掉 setTaskDecorator 那一行
→ 跑 ContextPropagationGuardTest → 必须失败，且报错里出现 EvolutionConfig.java:NN
→ 恢复 → 复跑必须转绿
```

使用 `cp` 备份而非 `git stash`（见 `tasks.md` §3）。

---

## S2 `TenantContext` 跨线程语义

### S2.1 现状核查（先确认有没有真缺陷）

| 路径 | 是否跨线程 | 租户身份怎么来 | 结论 |
| --- | --- | --- | --- |
| HTTP 请求链 | 否（同线程） | `TenantFilter` → `TenantInterceptor` 写 `ThreadLocal` | 正常 |
| 图执行 `GraphRunner.execute` | 是（`graph-*` 池） | **显式**从图状态取 `USER_ID` → `TenantContext.set(...)`，`finally` 中清空并还原旧值 | 正常 |
| 反思 `SkillReflector.reflect` | 是（`evolution-*` 池，`@Async`） | 租户由**方法参数** `tenantId` 传入，方法体不读 `TenantContext` | 正常 |
| 调度器（反思/记忆抽取） | 是（`@Scheduled`） | 遍历数据库，不读 `TenantContext` | 正常 |

**结论：当前不存在租户串号缺陷。** 遗留风险是**语义风险**——`TenantContext` 是 `ThreadLocal`，
跨线程读会**静默返回 `null`**（不抛异常），将来有人写出 `@Async` 方法读它，就是静默的错误归属。

### S2.2 方案：给"显式传递"提供一等公民 API

在 `TenantContext` 上新增：

```java
public record Snapshot(String tenantId, String userId, String role) {   // 允许全 null = 空快照
    public boolean isEmpty() { return tenantId == null && userId == null && role == null; }
}
public static Snapshot capture();            // 提交线程调用
public static void restore(Snapshot s);      // 执行线程 finally 调用；空快照 == clear()
```

`GraphRunner.execute()` 的手写三段（保存旧值 → `clear()` → 有旧值再 `set`）替换为
`Snapshot previous = TenantContext.capture();` + `finally { TenantContext.restore(previous); }`。

**为什么不是 `InheritableThreadLocal`**：线程池复用线程，子线程只在线程**创建**时继承一次，
池化后语义完全错乱（`TenantContext` 类注释已写明同款理由）。

### S2.3 被否方案：给 `evolutionExecutor` 也自动传租户

- 该池的提交者是 `ReflectionScheduler`（调度线程），它**本身没有租户身份** → 自动传播得到空快照，是**空操作**。
- 真正需要租户的是任务本身，而它已经通过 `reflect(sessionId, tenantId)` 显式传入。
- 自动传播会让"后台任务可以依赖当前线程的租户"变成错觉，**掩盖**未来真的写错的一处。
- 结论：**不传播**，并把"显式传递优先"写进类注释与决策记录。

### S2.4 守卫：`@Async` 方法不得读 `TenantContext`

源码扫描：定位 `@Async` 注解之后的第一个方法签名，取其方法体，断言方法体内不出现 `TenantContext.`。
同样带反恒真断言（至少扫到 1 个 `@Async` 方法）。
这条守卫是**静态近似**（只看直接引用，不追方法调用链），定位是 lint 而非证明——在 `checklist.md` 中如实标注。

---

## S3 `/actuator` 流量不进 Langfuse

### S3.1 问题

`management.tracing.sampling.probability=1.0` + Prometheus 每 15s 抓一次 `/actuator/prometheus`
→ 每次抓取一条根 span → Langfuse 多一条 trace（生产 ≈5760 条/天）。
实测影响：本轮 ADR-34 验证时，采样器产生的 64 条 actuator trace **把 trace 列表窗口挤满**，
导致首轮按 traceId 反查洞察 trace 未命中。

### S3.2 两个候选方案

| 方案 | 做法 | 影响面 | 结论 |
| --- | --- | --- | --- |
| A. 观测层拦截 | 注册 `ObservationPredicate`，`/actuator` 路径不创建 observation | **同时干掉 actuator 自身的 `http.server.requests` 指标**；且 predicates 是全局的，作用面比预期大 | 否 |
| B. 导出层拦截 | 在既有的 `SafeExporter`（ADR-24 的 deny-by-default 导出边界）里丢弃 actuator span | 只影响"进 Langfuse 的内容"，指标口径**零变化** | **采用** |

选 B 的理由：与本项目既有边界设计同构（导出边界本来就负责"什么能出网"），
且不会顺手改掉指标口径——静默改指标正是本项目反复强调要避免的那类"惊喜"。

代价（如实记录）：span 仍在本地创建（少量 CPU），只是不出网。若将来要省这部分开销，再评估方案 A。

### S3.3 判定规则（不依赖单一属性名）

一个 span 命中任一条件即视为 actuator 流量：任意**字符串属性值**包含 `/actuator`，
或 span 名称包含 `/actuator`。

**丢弃粒度按 traceId，不按单条 span**：命中后先记下该 span 的 `traceId`，然后丢掉该 trace 的**全部** span。
理由（实测，见 §S3.5）：一条 actuator trace 的根 span 名里带 `/actuator`，但它的子 span
（Spring Security 的 `secured request` / `authorize request` / `security filterchain`）**不含路径字样**——
只丢根会把子 span 留成无名 trace，trace 数量一条没少。记忆有上限（512），跨批次也能兜住。

### S3.4 验证

1. 单测：构造 `url.path=/actuator/prometheus` 的 span → 被丢；构造 `chat.pipeline` → 保留。
2. 真实环境：应用 + Langfuse 开，打 3 次 `/api/actuator/prometheus`，等导出，用平台 API 反查 →
   目标 0 条含 actuator 的 trace；同一时间窗内的业务 trace 仍在。

---

## S3.5 实测结果（2026-09-19，含未达标项）

> 管理端点前缀是 `/api/actuator/prometheus`（`server.servlet.context-path=/api`），
> 最初按 `/actuator/prometheus` 抓取得到 404，白跑一轮——记下来避免下次再踩。

| 方案 | 实测 | 结论 |
| --- | --- | --- |
| 只丢根 span（按名/属性匹配） | 3 次抓取 → **3 条无名 trace**，一条没少 | ❌ 等于只把 trace 改了名 |
| 按 traceId 整条丢（+短期记忆） | 3 次抓取 → **0 条具名 actuator trace**，残留 **1~2 条只含子 span 的无名 trace** | ⚠️ 部分达成 |
| 采样层丢弃（builder `setSampler`） | actuator 根 span 照旧出现 | ❌ 被 Boot 的 sampler customizer 覆盖 |
| 采样层丢弃（注册 `Sampler` Bean） | 残留仍在（3 抓 → 1 条含 2 个子 span 的无名 trace） | ❌ 未生效，已撤除代码 |

**根因**（探针直接读到，非推断）：actuator 请求的 span 树是
`http get /actuator/prometheus`（根）+ `security filterchain before/after`、`secured request`、
`authorize request`（子，自身不含路径字样），**同一条 trace**。
子 span 先结束、先被导出，因此当它们在某个批次里先到时，根 span 的 traceId 还没被学到
→ 记忆无法回溯命中 → 留下无名的孤儿子 span。这是**导出时序**问题，不是匹配规则问题。

**未达标**：目标"0 条 actuator 相关 trace"**未达成**，实测残留 1~2 条/3 次抓取（≤2 个 span/条）。

**下一步候选**（本轮未做，需另立假设再验证）：
① 让 actuator 请求不经过被观测的过滤器链（独立管理端口 + 该端口关 tracing）；
② 找到能让 Boot 认账的采样器注入点（需先查清 Boot 的 customizer 顺序，本轮未查）；
③ 接受现状：相比改造前（3 次抓取 = 3 条完整 trace），绝对条数已下降。

