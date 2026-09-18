# checklist — ADR-31 收尾：准入完备性（发现二 / 发现三）

验收标准（AC）。一行一条，可独立判定通过与否。**未通过项必须在汇报中显式说明原因。**

**结论（2026-09-17，2026-09-18 / 2026-09-19 追加）**：A–G 全部通过；F6 的三个未验证项中
**第 3 项（反思容量让路分支）已于 2026-09-18 实证关闭并顺带修复一处缺陷，见 H 段**；
**第 2 项（permit 持有代价）已于 2026-09-19 量化关闭，见 I 段**（结论：代价 ≈ 0）；
第 1 项仍挂账（Langfuse 实例未运行）。

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
      2. ~~**permit 持有时间变长的代价未量化** —— 只验证了无功能回归，未做负载对照。~~
         → **✅ 2026-09-19 已量化（代价 ≈ 0，低于分辨率），并顺带推翻该建议的一个收益前提，见 I 段。**
      3. ~~**反思的容量让路分支未触发** —— 本次 `容量推迟=0`，该 WARN 分支仅有单测级保障。~~
         → **✅ 2026-09-18 已通过故障注入真实触发（`容量推迟=30`），并因此炸出并修掉一处真缺陷，见 H 段。**

## H. 追加收尾：容量让路分支实证 + 一处映射缺陷（2026-09-18）

> 起因：F6.3 是 F 段最后一个未验证项。做"反思撞闸门"故障注入时，该分支**没能触发**——
> 顺着查下去发现不是环境问题，而是 ADR-31 建议 3 的**代码缺陷**。详见 `docs/09` §8.11。

- [x] H1 故障注入设计可排除其他拒绝来源：假上游 hang（请求永不返回）+ `gate=1` / `wait-ms=0` /
      `max-concurrent-calls=1` + 自适应与熔断**均关闭** + 三个 timeout 调 180s +
      `yield-to-online=false`（否则反思主动让路，测不到撞闸门）
- [x] H2 副作用受控：`evolution.quality-threshold=101` → 提取结果全被质量过滤，只写 skip mark，
      **不污染技能库**
- [x] H3 **对照实验（硬规矩）**：修复前 `REFLECTION_DEFERRAL=FAIL`（WARN=0 / ERROR 全栈=6）；
      修复后 `PASS`（WARN=**30** / ERROR 全栈=**0**）
- [x] H4 单测层对照：新测试 `syncCapacityRejectionIsMappedToBizException4003` 在修复前
      **精确失败**（`Tests run: 1, Failures: 1`），修复后通过 —— 不是"测空气"
- [x] H5 修复：`LlmGateway.call()` 最外层闸门拒绝由裸 `throw new CapacityException()`
      改为 `throw publicFailure(new CapacityException())`（映射为 `BizException(4003)`）
- [x] H6 不变式确认：`retryable` / `fallbackAllowed` 对 `CapacityException` 仍为 `false`
      （容量拒绝不可重试、不可降级，本次未改）
- [x] H6.1 判据校准（2026-09-19 追加）：不变式精确表述为"**出网关**的异常都是 `publicFailure()` 的产物"，
      自查看**位置**不看文本 —— `syncAttempt()` 内的裸抛会被 `callWithRetries` 末尾兜住，
      `grep "throw new CapacityException"` 会误报；只有映射边界外的 `call()` 最外层与 `stream()` 订阅入口
      必须自己映射（详见 `docs/09` §8.11(3)）
- [x] H7 全量单测 **207/207**（基线 206 + 新增 1）
- [x] H8 真实 E2E `scripts/e2e_live.py` **22/22**（`outputs/e2e-live-adr31-final.json`）
- [x] H9 `docs/09` 新增 §8.11；本节 F6.3 据实划掉；ADR-31 补"实施补记"段
- [x] H10 提交并推送；工作区干净

## I. 追加收尾：permit 持有代价量化（2026-09-19）

> 起因：F6.2 是 F 段剩下的未验证项之一。翻 `outputs/` 时发现
> `adr31-permit-cost-{before,after}.json` **已经存在**——实验跑过，但结论从未写进 docs，
> 于是"未量化"一直挂着。查下去发现**装置本身有三个缺陷**，那次实验从根上不成立。

- [x] I1 装置缺陷 1：假上游 `max` 是**进程内累计最大值**，用例间从不重置
      → 先跑的把 max 顶高，后跑的**结构上不可能**超过它。
      证据：2026-09-18 两份产物 `fake_stats.max_at` **完全相同**（`1789717063.9246786`，都是 4）。
      修：`scripts/fake_llm.py` 新增 `/reset`；探针基线前强制 reset 并**校验清零成功**，
      未清零则 `SystemExit` 拒绝产出数据（不让"装置坏了"伪装成"无差异"）
- [x] I2 装置缺陷 2：运行脚本用 `git checkout HEAD` 当"改造前" → 修复一旦提交，
      `HEAD` 即含修复，两轮跑同一份代码。修：改用显式分界提交 `ADR31_REF=49ff0c0^`
- [x] I3 装置缺陷 3：装置在 `logs/`（gitignore）→ 实验无法从仓库复现。
      修：迁至 `scripts/`（`fake_llm.py` / `probe_permit_cost.py` / `run_permit_cost_ab.sh`），
      并顺带把反思让路实验的装置也一并迁走（`run_reflection_defer.sh` / `probe_reflection_deferral.py`）
- [x] I4 换源码做对照**自带还原**：`trap` + md5 校验，无论怎么退出都还原 `LlmGateway.java`
      （本仓库禁止 `git stash`）。实测：两轮跑完 md5 与开工前一致
- [x] I5 对照轮次 ①（准入=网关=4，退避 200ms）：AFTER vs BEFORE **逐项全等**
      （上游峰值 4、准入 14/55、4003×55 / 5000×12、`fake total` 40）
- [x] I6 灵敏度对照轮次 ②（退避放大到 2000ms，让出窗口 ×10）：差异仍在噪声内
      （4003 61 vs 60，且那 1 个是 `RemoteDisconnected`）→ 证明**不是分辨率问题**
- [x] I7 对照轮次 ③（准入 8 > 网关 4，想构造网关真正 bind 的场景）：
      **应用拒绝启动** —— `CapacityGuard` 硬校验 `max-inflight <= max-concurrent-calls`
- [x] I8 对照轮次 ④（**加入后台争抢**：准入=网关=4、退避 2000ms、3 个 `/insight/analyze` 线程）：
      **终于出现差异** —— 用户 4003 62→66（持平），但**拒绝点搬家**：BEFORE 主要在准入层拒（55/62），
      AFTER 主要在网关层拒（65/66）；按"已准入请求"归一化，网关拒绝率 **50% → 96%**；
      上游调用 43→31；**后台成功 71→53（−25%）→ 后台确实让路了**
- [x] I9 **为什么前三轮必然无差异（纯用户流量）**：`CapacityGuard` 保证 准入 ≤ 网关，故
      ①准入<网关：网关永不 bind；②准入=网关=N：某请求重试时自己不持许可、其余**用户**在途至多 N−1
      → 至少 1 个空闲，重试必然拿得到
- [x] I10 ⚠️ **但 I9 的前提被实测打破**：`OnlineLoadTracker.admit()` 全仓**只在 `ChatEntry`** 被调用，
      而**后台任务（反思 / 洞察）经网关却不经准入** → 它们能占走网关许可，
      使用户请求的重试**真的拿不到**。这正是轮次 ④ 出现差异的原因，也是前三轮的**边界条件**
- [x] I11 "厂商侧瞬时在途突破容量口径"在纯用户流量下亦不成立（应用侧在途 ≤ 准入 ≤ 网关，
      且同一请求的多次尝试**串行**）。仅可能在①后台争抢、或②**客户端中断而厂商仍在处理**时发生；
      后者**两版都修不掉**，需厂商侧取消或独立核算
- [x] I12 **代价结论**：**纯用户流量下 ≈ 0**（轮次 ① 差 0、② 差 1，低于分辨率）；
      代价只在后台争抢时显现，表现为**许可周转率下降**（已准入请求的网关拒绝率 50% → 96%），
      失败形态从"慢链路"变为"快速失败"（准入周转 14 → 68）
- [x] I13 **收益结论**：不是"修复越界"，而是 ①账目可读性/准入原子化（一次请求 = 恰好一次 `tryAcquire()`）
      ②争抢下**把容量从后台还给用户链路**（后台成功 −25%）。本轮**不回退**建议 3
- [x] I14 **新发现（未闭环，需产品决策）**：后台任务绕过准入闸门 → 准入层算出的"容量"
      并不覆盖全部网关使用者。是否让后台也过准入、或给后台单独配额，另行评估
- [x] I15 `docs/09` 新增 §8.12（装置三缺陷、四轮对照表、`CapacityGuard` 边界条件、后台绕过准入）；
      ADR-31 的"已知代价"改为实测数字并**修正发现二的表述**（旧源码里重试**会**重新 `tryAcquire`）；
      本节 F6.2 据实划掉
- [x] I16 提交并推送；`ls-remote` 复核远端 == 本地

## G. 明确不做（防范围蔓延）

- [x] G1 未改 AIMD 收缩/回升算法与闸门数值
- [x] G2 未改熔断判定逻辑
- [x] G3 未引入后台任务优先级/排队机制
- [x] G4 未改检索 / 提示词 / 业务编排
- [x] G5 未做多实例容量协调（ADR-23 既定）
