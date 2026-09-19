# tasks — 调度器路径的 trace 父上下文传播（ADR-34）

**立项**：2026-09-19
**前置阅读**：ADR-23（唯一准入点）、ADR-24（可观测口径·不采正文）、ADR-31（建议 3·4）、ADR-33（降噪）
**来源**：`docs/09` §8.13(2)(4) 的挂账 —— 反思任务的 `llm.attempt` **归属断裂**（断点已静态定位）

---

## 1. 目标

让**调度器/`@Async` 后台路径**产生的模型调用，与 HTTP 入口一样**挂到同一条 trace 下**，
从而关闭"只能靠时间戳邻近推断归属"这一可观测性盲区。

| 现状 | 目标 |
| --- | --- |
| 反思任务的 `llm.attempt` **自成新根 trace**（`{http post, chat glm-4-flash, llm.attempt}`），任务 span 里查不到它 | `llm.attempt` 成为任务 span 的**子 observation**，同一 trace 内可完整追溯 |
| 归属只能靠时间戳邻近推断（`07:26:35.307` → `07:26:35.383`） | 平台侧按 traceId 一次查全 |

## 2. 边界

### 做什么

1. **补 ADR**：`docs/03` 目前只规定"检索/advisor 路径"的父上下文策略，**从未覆盖调度器路径** ——
   按"文档即事实源"，先补 ADR-34 再改代码。
2. **实现**：给 `evolutionExecutor` 加 `TaskDecorator`，在**提交线程**捕获父 `TraceContext`，
   在**执行线程**建立作用域后再跑任务。
3. **单测**：把"父上下文跨 `@Async` 边界仍可见"钉住（用真实 executor + 真实装饰器）。
4. **平台复验**（硬性）：起真实应用 → 触发反思 → 在 Langfuse 平台按 traceId 确认
   `llm.attempt` **挂在任务 trace 内**，而不是自成新根。
5. **收尾**：推送本地未推送的 `da69519`；更新 `docs/09` §8.13(4) 与 checklist。

### 不做什么

- 不改 `LlmGateway` 的准入/熔断/重试逻辑（ADR-31/32 成果不动）。
- 不改 HTTP 入口的传播方式（`ChatExecutor` 的显式传参形态保留）。
- 不引入 `TransmittableThreadLocal` / Reactor 全局 hook（ADR-24 既定：只用显式边界传播）。
- 不改 `TenantContext` 的异步语义（另有独立考量，本次不碰）。
- 不做跨实例/跨 MQ 的上下文传播。

## 3. 拆分

| # | 事项 | 产出 |
| --- | --- | --- |
| T1 | 三件套 | `docs/phase6-trace-propagation/{tasks,spec,checklist}.md` |
| T2 | ADR-34：调度器路径的父上下文策略 | `docs/03-技术决策记录.md` |
| T3 | `TraceContextTaskDecorator`（observability 包） | 新类 |
| T4 | `EvolutionConfig.evolutionExecutor()` 挂装饰器 | 改配置 |
| T5 | 单测：跨 `@Async` 边界父上下文可用 | `TraceContextTaskDecoratorTest` |
| T6 | 验证：编译 → 全量单测 → 真实 E2E | 证据 |
| T7 | **平台复验**：反思 `llm.attempt` 挂进任务 trace | Langfuse 产物 |
| T8 | 文档：§8.13(4) 结论更新为"已修复并平台确认"；checklist 更新 | docs |
| T9 | 推送 `da69519` + 本次提交；记忆更新 | git / memory |

## 4. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 装饰器影响所有经该 executor 的任务（行为面扩大） | 全仓只有 `SkillReflector.reflect` 用 `@Async("evolutionExecutor")`；无父上下文时装饰器**直接返回原任务**（零开销、零行为变化） |
| 新增 wrapper span 改变平台 trace 结构 | 这是**改进**（任务 span 才可见）；不改变既有 span 的名称/属性/层级 |
| 上下文在子线程泄漏到线程复用的下一个任务 | 作用域用 try-with-resources 关闭；wrapper span 在 finally 结束 |
| `AiTelemetry` 注入进 `@Configuration` 引发循环依赖 | `AiTelemetry` 只依赖 `Tracer`，不依赖任何业务 Bean |

## 5. 完成定义

编译通过 + 全量单测全绿（≥207）+ 真实 E2E 22/22 + **平台侧确认归属不再断裂** + 提交推送 + 工作区干净。
