# Phase 6 遗留收敛 —— 任务拆分（task）

> 立档：2026-09-19。上游：ADR-34（`33d3ff6`）收工后剩下的三项**非缺陷遗留**。
> 本文件只回答"做什么、不做什么、边界在哪"；技术方案见 `spec.md`，验收见 `checklist.md`。

## 0. 为什么现在做这三项

ADR-34 修好的是"**已经发生**的归属断裂"。剩下这三项都不是当前故障，而是：

| 项 | 性质 | 不做的后果 |
| --- | --- | --- |
| T1 | **防护缺口** | 将来新增 `@Async` 线程池忘了挂装饰器，断裂原样复活，**没有任何测试会报错** |
| T2 | **语义含糊** | 跨线程读 `TenantContext` 会静默拿到 `null`（而不是报错）→ 数据写错归属，且不报错 |
| T3 | **噪声成本** | `/actuator/prometheus` 每次抓取产生一条 trace，生产 ≈5760 条/天，把 Langfuse 的 trace 列表窗口挤满 |

三项都**不是** bug fix，因此判据是"**将来不再复发**"，不是"现在修好了某个错"。

## 1. 目标（做什么）

### T1 —— 跨线程上下文守护（源码扫描）

- 新增测试：扫描 `src/main/java`，**每个创建 `ThreadPoolTaskExecutor` 的 `@Bean` 方法都必须调用 `setTaskDecorator(...)`**。
- 豁免必须**显式登记**，且每条豁免要写明理由（不允许"顺手加一行"的隐式豁免）。
- 反向对照实验：临时摘掉 `EvolutionConfig` 的 `setTaskDecorator` → 该测试**必须失败且指名到文件**；恢复后转绿。

### T2 —— `TenantContext` 跨线程语义

- 给 `TenantContext` 增加**一等公民的跨线程原语**：`capture()` / `restore(Snapshot)`。
- `GraphRunner.execute()` 的手写"存旧值 → 清空 → 还原"改为使用该原语（行为必须完全等价）。
- 补单测覆盖：快照/还原往返、空快照还原等于清空、嵌套还原。
- 在类注释里把规则写死：**跨线程边界不依赖 `ThreadLocal` 自动继承，必须显式传递**（首选方法参数，其次 `capture`/`restore`）。
- 守卫：扫描 `@Async` 方法体，**不得直接读 `TenantContext`**（防止将来静默拿到 `null`）。

### T3 —— `/actuator` 流量不进 Langfuse

- 在**导出边界**（`SafeExporter`）丢弃 actuator 相关 span：不污染 Langfuse，又不动指标口径。
- 补单测：actuator span 被丢、业务 span 保留、丢的是整条而不是部分属性。
- 真实环境验证：起应用 + Langfuse 开，人为抓取 `/actuator/prometheus` 若干次，反查平台**确认 0 条** actuator trace。

## 2. 非目标（明确不做）

1. **不做** `TenantContext` 向 `evolutionExecutor` 的自动传播。理由见 `spec.md` §S2.3：反思任务的提交者是调度线程（本身无租户身份），且租户已由参数显式传递；自动传播今天是空操作，却会让"后台代码可以依赖 ThreadLocal"变成一种错觉。
2. **不改** 采样率 / 不动 `LlmGateway` / 不动容量与准入参数（ADR-29/31/32 已闭环，禁止顺手调参）。
3. **不做** S9 验收口径拍板（需产品决策）。
4. **不清** `logs/` 历史日志（约 2.5GB，属用户数据，需单独确认）。
5. **不重评** rerank（两轮实测负收益，已定：默认关闭）。
6. **不引入** 新的观测依赖或新的 MCP / 外部服务。

## 3. 边界与约束

- **文档先行**：本目录三份文档写完才动代码。
- **禁 `mvn clean`**：`target/classes/application-local.yml` 是 LLM 凭据载体（gitignore，不入库）。
- **禁 `git stash`**：本仓库 `.git` objects 不完整，`stash push -u` 会清空 `refs/heads/main`。
- 脚本中**禁用 `rm`**（平台 safe-delete 会把文件移走导致静默错误结论）。
- 端口：mcp-server 用 **8300**（8125 落在 Windows 保留段），主应用端口从启动日志读。
- T3 的平台侧验证**需要 Langfuse 在跑**（本次已确认 `localhost:3000` 可达）。

## 4. 交付物

| 类型 | 路径 |
| --- | --- |
| 代码 | `TenantContext`（新增原语）、`GraphRunner`（改用原语）、`LangfuseTracingConfig`（导出边界过滤） |
| 测试 | `ContextPropagationGuardTest`（新）、`TenantContextTest`（扩）、`LangfuseTracingTest`（扩） |
| 文档 | 本目录三件套 + `docs/03` 决策记录 + `docs/09` 测试策略 |
| 证据 | `outputs/` 下的单测/E2E/Langfuse 反查 JSON |
