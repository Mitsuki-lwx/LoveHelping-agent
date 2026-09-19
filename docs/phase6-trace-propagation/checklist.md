# checklist — 调度器路径的 trace 父上下文传播（ADR-34）

验收标准（AC）。一行一条，可独立判定通过与否。

**结论（2026-09-19）**：全部通过。未验证项见 F8。

---

## A. 前置（硬性流程）

- [x] A1 三件套齐备：`docs/phase6-trace-propagation/{tasks,spec,checklist}.md`
- [x] A2 断点证据链逐条核对过源码（`docs/09` §8.13(4) 第 2–5 步），非凭记忆
- [x] A3 **先补 ADR-34**（`docs/03`）：调度器路径的父上下文策略此前**文档未覆盖**

## B. 实现

- [x] B1 新增 `TraceContextTaskDecorator`（`infrastructure/observability`）
      —— 在**提交线程** `capture()`，在**执行线程** `start + scope`，`finally` 结束 span
- [x] B2 无父上下文时**直接返回原 Runnable**（`assertSame` 已验证）
- [x] B3 `EvolutionConfig.evolutionExecutor()` 挂上装饰器（span 名 `task evolution.reflect`）
- [x] B4 装饰器不改变任何既有 span 的名称/属性/层级（只新增一层容器 span）
- [x] B5 未引入全局 hook（无 `TransmittableThreadLocal` / Reactor hook / `ContextPropagating*`）

## C. 单测（可区分改造前后的行为断言）

- [x] C1 V1 跨线程：任务内 `capture().traceId()` == 父 traceId（改造前为 null）
      + 容器 span 的 `parentId` == 父 `spanId`
- [x] C2 V2 父子关系：任务内新建 span 落在同一条 trace（由 V1 的 innerTraceId 断言覆盖）
- [x] C3 V3 无父上下文：`decorate()` 返回**同一个** Runnable 实例，不建 span
- [x] C4 V4 作用域不泄漏：任务结束后工作线程 `capture()` 恢复为 null
- [x] C5 **对照实验**：装饰器改为"永不包装"后，C1 精确失败（`Tests run: 3, Failures: 1`），恢复后通过

## D. 全量验证（硬性：编译 + 单测 + 真实 E2E）

- [x] D1 `mvn -o -DskipTests compile` 通过
- [x] D2 全量单测 **210/210**（207 + 本次新增 3）
- [x] D3 真实 E2E `scripts/e2e_live.py` **22/22**（`outputs/e2e-live-adr34.json`）
- [x] D4 应用启动日志索引零重建（`added=0 replaced=0 skipped=131`，启动 23.2s）

## E. 平台复验（本项结案的判据）

- [x] E1 起应用（Langfuse 开）并触发反思（`extract-delay-seconds=30` / `reflect.fixed-delay-ms=15000`
      / `quality-threshold=101` 以免污染技能库）
- [x] E2 按反思任务 traceId 查平台：`llm.attempt` **出现在该 trace 的 observations 内**
      —— trace `e1bc8b069ed19237`，`obs=33`，含 `llm.attempt` × 7
- [x] E3 该 trace 含装饰器建立的 `task evolution.reflect` × 13（子链正确）
- [x] E4 观察形状**不再是** `{http post, chat, llm.attempt}` 的孤立新根
      —— **孤立根 26 → 0**
- [x] E5 对照：改造前同一场景 `llm.attempt` 自成新根（`outputs/adr31-langfuse-probe.json` 26 条）
- [x] E6 HTTP 入口（洞察）trace 结构**不变**（`INSIGHT_ADMISSION=PASS` 4/4，`outputs/adr34-insight.json`）

## F. 文档与收尾

- [x] F1 `docs/09` §8.13(4) 说明块更新为"已修复并平台确认，见 §8.14"
- [x] F2 `docs/09` 新增 §8.14（含改造前后轨迹对照表与层级图）
- [x] F3 `docs/phase6-adr31-followup/checklist.md` 的 J9 挂账标记为已关闭并指向本次
- [x] F4 **推送本地未推送的 `da69519`**
- [x] F5 本次提交并推送；`ls-remote` 复核远端 == 本地；工作区干净
- [x] F6 提交前密钥扫描：无真实密钥入库
- [x] F7 记忆更新（不变式 + 新踩坑）
- [x] F8 **明确列出未验证项**：
      1. **只覆盖 `evolutionExecutor`** —— 当前全仓仅 `SkillReflector.reflect` 一处用 `@Async`，
         实际覆盖面就是它；**将来新增别的 `@Async` 线程池需同样挂装饰器**，
         该约束已写入 ADR-34 决策，但**没有架构守护测试**兜底（建议后续补，参照
         `AdmissionCompletenessTest` 的源码扫描形态）。
      2. **`TenantContext` 的异步语义未涉及** —— 装饰器只传 trace 上下文；租户上下文
         本次未改动、也未验证（ADR-13 已冻结租户维度，`SkillReflector` 显式传 `"default"`）。

## G. 明确不做（防范围蔓延）

- [x] G1 未改 `LlmGateway` 的准入/熔断/重试逻辑
- [x] G2 未改 HTTP 入口的传播方式（`ChatExecutor` 显式传参保留）
- [x] G3 未改 `TenantContext` 的异步语义
- [x] G4 未做跨实例/跨 MQ 的上下文传播
- [x] G5 未引入新的第三方依赖
