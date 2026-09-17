# checklist — ADR-31 收尾：准入完备性（发现二 / 发现三）

验收标准（AC）。一行一条，可独立判定通过与否。**未通过项必须在汇报中显式说明原因。**

**结论（2026-09-17）**：全部通过。未验证项见 F6。

---

## A. 前置（硬性流程）

- [x] A1 三件套齐备：`docs/phase6-adr31-followup/{tasks,spec,checklist}.md`
- [x] A2 spec 中的现状描述**逐行核对过源码**（含行号），非凭记忆

## B. 发现三 — 两处改为走网关

- [x] B1 `EvolutionConfig.skillReflector` 的注入由 `@Qualifier("openAiChatModel")` 改为 `@Primary ChatModel`
- [x] B2 `InsightService` 构造器同上
- [x] B3 全仓 `@Qualifier("openAiChatModel")` 仅剩 `LlmGateway` 自身一处（合法例外）
      —— 另发现并修掉 ADR-31 原文遗漏的**第三处**：`GoldenSetRunner`（judge model）
- [x] B4 启动无装配歧义（启动 14.7s 成功，无 `NoUniqueBeanDefinitionException` / 无循环依赖）
- [x] B5 `SkillReflector` 的容量类拒绝（4003）降级为 WARN 单行，**不打全栈**
- [x] B6 `SkillReflector` 的非容量异常仍打 ERROR 全栈（不误伤真故障排查）

## C. 发现二 — permit 覆盖整条链路

- [x] C1 同步 `call()`：permit 在最外层获取、覆盖重试 + 降级全程
- [x] C2 流式 `stream()`：permit 在订阅入口获取，`doFinally` 释放（覆盖 complete / error / cancel）
- [x] C3 `syncAttempt` worker 内的 acquire/release 已移除
- [x] C4 `streamAttempt` 内的 acquire 与三处 release 已移除
- [x] C5 AIMD 信号（`onSuccess` / `onThrottled`）仍按 attempt 触发（未被误删）
- [x] C6 同步路径 `blocking` 池的 `RejectedExecutionException → CapacityException` 保险保留
- [x] C7 `CapacityException` 仍不外溢为可重试/可降级（`retryable` / `fallbackAllowed` 均为 false，未改）

## D. 单测（可区分改造前后的行为断言）

- [x] D1 V1：同步重试的 backoff 窗口内 `llm.inflight == 1`
      —— **对照实验证明有效**：旧代码下 `expected: <1.0> but was: <0.0>`
- [x] D2 V2：重试等待期间许可**不被易主**（第二个请求收到 4003）
      —— **对照实验证明有效**：旧代码下无 4003（`null`）
- [x] D3 V3：`maxConcurrentCalls=1` 下连续 3 次失败请求不累积占用许可（`inflight == 0`）
- [x] D4 V4：`AdmissionCompletenessTest` 断言 `@Qualifier("openAiChatModel"|"deepSeekChatModel")`
      仅存在于 `LlmGateway.java`（另加"至少命中一处"防止扫描失效导致假通过）
- [x] D5 既有 `LlmGatewayTest` 全部保持通过（`cancellationReleasesStreamCapacity`、
      `hangingSyncWorkIsBounded`、`sharedRetryBudgetBoundsStorm` 等）

## E. 全量验证（硬性：编译 + 单测 + 真实 E2E）

- [x] E1 `mvn -o -DskipTests compile` 通过
- [x] E2 全量单测 **206/206**（基线 202 + 本次新增 4）
- [x] E3 真实 E2E `scripts/e2e_live.py` **22/22**（`outputs/e2e-live-adr31.json`）
- [x] E4 运行时证据：`POST /insight/analyze` 后 `llm.call` Δ=1（证明 InsightService 经网关）
- [x] E5 **（判据已修正）** 运行时证据，原写"全程 `llm.inflight ≤ llm.permits.limit`"**不成立**——
      ADR-32 的 AIMD 收缩用 `reducePermits` 让可用数转负，收缩瞬间 `inflight > limit` 是**设计**。
      实际采用能区分改造前后的两条：
      - [x] E5a 调用期间 `llm.inflight ≥ 1`（洞察峰值 1、反思峰值 1）→ 占用网关许可，即走了网关；
            改造前（直连裸模型）该值恒为 0
      - [x] E5b `llm.inflight` 峰值 ≤ 24（配置硬上限）
- [x] E6 应用启动日志索引零重建（`added=0 replaced=0 skipped=131`，启动 14.7s）
- [x] E7 **额外**：反思任务端到端证据 `llm.call` Δ=**30**、20 个会话落定、真错误 0
      （`outputs/adr31-reflection-admission.json`）

## F. 文档与收尾

- [x] F1 ADR-31 状态更新为"已全部实施"，并记录建议 3·4 的实施路线与代价
- [x] F2 `docs/09` 新增 §8.10（含对照实验与运行时证据）
- [x] F3 提交并推送；工作区干净
- [x] F4 提交前密钥扫描：无 `sk-` / `pk-lf-` / `sk-lf-` / 真实 token 入库
- [x] F5 项目记忆更新（不变式 + 新踩坑）
- [x] F6 **明确列出未验证项**：
      1. **Langfuse 平台侧复验未做** —— 自托管实例本次未运行（`/api/public/health` = 000）；
         三处走网关的调用在平台侧应能看到 `llm.attempt` 子 observation，待实例启动后复验。
      2. **permit 持有时间变长的代价未量化** —— 只验证了无功能回归，未做负载对照。
      3. **反思的容量让路分支未触发** —— 本次 `容量推迟=0`，该 WARN 分支仅有单测级保障。

## G. 明确不做（防范围蔓延）

- [x] G1 未改 AIMD 收缩/回升算法与闸门数值
- [x] G2 未改熔断判定逻辑
- [x] G3 未引入后台任务优先级/排队机制
- [x] G4 未改检索 / 提示词 / 业务编排
- [x] G5 未做多实例容量协调（ADR-23 既定）
