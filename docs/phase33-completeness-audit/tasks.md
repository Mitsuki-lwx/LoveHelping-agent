# phase33 · 完整性审计 + 测试流程缺口（**先审计，不动手**）

> 起因（Mitsuki）：
> 「扩展情感……肯定是大工程；要在**完全完善了当前**的情况下再说吧。要确保当前的各种需求能
> **完全实现**，并且有**专业完善的软件测试流程**测试之后再说，甚至如果现在**头部领域有更好的
> 做法可以替换我们的某一模块**也是可以尝试的」。
>
> ⛔ 本文只做**审计 + 计划**，不改任何代码。所有结论附**实测证据**。

## A. 需求完整性（对着 `docs/11-软件需求规格说明书.md`）

### A1 状态总表：14 项，12 ✅，**2 项 🚧**

| 需求 | SRS 状态 | 实测 |
|---|---|---|
| FR-COMM-07 删除对话 | ✅（软删）/ 🚧（硬删 ADR-5） | `MemoryController` 有 `@DeleteMapping("/{conversationId}")`（第 325 行）→ **软删已实现**；⛔ **硬删/级联删向量未做** |
| FR-COMM-08 清空全部历史 | 🚧 | ⛔ 未见对应端点 |
| 其余 12 项 | ✅ | 未见反例（本轮只做静态核对，**未逐项跑用例**）|

另有 `DeleteService.deleteUserData(userId)`（**账号级**删除）：它会物理删 `user_memory`/`conversation_summary`/
`agent_task`/`vote`/`sandbox_*`/`insight_record`，但**消息只做软删**（`set("deleted",1)`）。
⛔ **"注销账号"与"删干净"是两件事** —— 若承诺过可删除，软删是否算删除需要**明确口径**（未决）。

### A2 ⛔ **SRS §7 可追溯性表与代码偏离**（AGENTS.md §1 禁止）

§7 把 **CORE-01（话术三级）/ CORE-02（情绪刹车片）标为"（待立项）"**，
而 §2.4 状态表说两者 ✅ —— **同一份文档自相矛盾**，且代码里早已实现：
`ChatExecutor.MAX_ADVICE_TIERS` + `ADVICE_ACTIVATE_PROMPT`、`GuardrailRuleService.matchesEmotionBrake`，
且有 ADR-18 记录。⇒ **§7 是过时的**。

### A3 冒烟缺项（`docs/09` §7.8 自己登记过，仍未补）

创建对话显式用例 / 删除清空用例 / 赞踩反馈与技能萃取断言 / 情绪刹车片 —— 文档写着"待补充（随实现）"，
而实现**早已完成**。⇒ **缺的是用例，不是功能**。

## B. 测试流程（对着 `docs/09-测试策略.md`）

| 策略条款 | 要求 | 实测现状 | 判定 |
|---|---|---|---|
| §0 DoD | 编译+单测+E2E | 有，且本轮一直照做 | ✅ |
| §2 单元测试 | — | **367 个 / 48 个类** | ✅ |
| §3 **集成测试** | **testcontainers** | ⛔ **0 个 `*IT`，`pom.xml` 里没有 testcontainers 依赖** | ⛔ **整层缺失** |
| §4 对抗用例集 | 安全验收 | 护栏离线标定 + E2E A5/A6 | ✅（部分）|
| §5 Golden Set | prompt 回归 | `golden-set.json`；Langfuse 里已有 `golden-set` **dataset** | 🟡 有数据，**未见门禁** |
| §5.5/5.6 检索/生成质量 | 指标回归 | `run_retrieval_eval.sh`、`answer_eval.py` | ✅ |
| §6 性能 | 场景 + 计划 | `loadtest.py` + §6.1 性能工程计划 | 🟡 部分 |
| §7 E2E 冒烟 | 清单 | 本地 `e2e_live.py` **21 项**；CI 跑 **另一套 bash `e2e-smoke.sh`（章节 7.1/7.2/7.3/7.4/7.6）** | ⛔ **两套分叉** |
| — | 覆盖率 | ⛔ **无 JaCoCo** | ⛔ 缺 |
| — | 静态检查 | 只有 gitleaks（`security.yml`） | 🟡 缺 SpotBugs/ErrorProne/Checkstyle |

### B1 ⛔ 最该修的两条

1. **CI 与本地 E2E 分叉**：CI 覆盖 **7.1/7.2/7.3/7.4/7.6**，**缺 7.5（Agent）、7.7（记忆系统）**，
   也没有本地 `e2e_live.py` 里的**沙盘 / 三牌协议 / 提示词探查**。
   ⇒ **"CI 绿"不等于"本地那 21 项绿"** —— 两道门给出不同答案，是**流程级缺陷**。
2. **集成测试整层缺失**：策略明写 testcontainers，实际 0 个。当前 48 个测试类**全是 Mockito 单测**；
   唯一接近集成的是 E2E（需真起 MySQL/PG/Redis/MCP + 真 LLM key）→ **CI 里跑它要靠一堆 service 容器**。
   ⇒ 中间缺一层"**用真容器测持久化/SQL/Flyway/事务**"的测试（ADR-46 的"载荷契约"事故本可由它拦住）。

## C. 行业做法可替换候选（按收益排）

| # | 现状 | 更好的做法 | 收益 |
|---|---|---|---|
| **C1** | 集成测试靠 E2E + 手搓 `HttpServer` stub | **Testcontainers**（策略里已写） | 补上缺的那层；SQL/Flyway/事务/持久化可在 CI 里真验 |
| **C2** | CI 一套 bash + 本地一套 python | **收敛成一套**（python 那套更全） | 消除"两套门" |
| **C3** | 契约是手写 `docs/05` | **契约测试**（或从代码生成契约文档） | 防"文档与实现偏离"（A2 就是这类）|
| **C4** | 无覆盖率 | **JaCoCo + CI 阈值** | 让"没测到"可见 |
| **C5** | 评测脚本各自为政（`answer_eval.py` 等） | **收敛到 Langfuse dataset/experiments**（实例里已有 `golden-set`） | 评测版本化、可比、可回溯（本仓反复吃"口径不可比"的亏）|
| **C6** | 手写 Langfuse OTLP 导出 + 属性白名单 | 换标准 OTel 导出 | ⛔ **不建议**：白名单是刻意的隐私边界（ADR-62 §八），换掉会丢它 |
| **C7** | 静态检查只有 gitleaks | SpotBugs / ErrorProne / Checkstyle | 廉价增量 |

## ① E2E 收敛 ✅ 已落地（ADR-68）

| 动作 | 结果 |
|---|---|
| 新 `scripts/e2e_assertions.py`（可移植） | A3/B/D1-D5/A4/A5(10 用例)/E；**判据强度逐条对齐原 bash 版** |
| `run_phase22_e2e.sh` | 删 ~110 行内联 heredoc，改调模块；A6 仍走共享 `probe_l3_stream.py` |
| `ci.yml` | E2E 步骤改跑 **`e2e_live.py` + `e2e_assertions.py` + `probe_l3_stream.py`**（与本地同一套）|
| `scripts/e2e-smoke.sh` | **已删除**；4 处文档引用同步改 |
| 验证 | **本地真跑**：核心 **22/22** + 附加 **17 通过 / 0 失败**（A6：被替换 0/6、内容完整 6/6、L3 拦截 0）|

⛔ 途中我犯的错（第 5 次「量具自伤」）：把原版**只打印不判定**的 E 升成 `ERROR==0` 硬判据 →
首跑 FAIL（10 条全是 Langfuse OTLP 导出失败 = **装置形态**）。已改为只判**应用层** ERROR，
噪声单列可见。**CI 未实跑**（本地无法触发 Actions）—— 已验证 YAML 结构 + 同一套命令在本地真跑。

## ② 修 SRS §7 ✅ 已落地

- **§7 可追溯性表**：`CORE-01/CORE-02` 从「（待立项）」改为**实测实现点**
  （CORE-01：`CapabilityRouter.isAdviceRequest` → `ChatExecutor.ADVICE_ACTIVATE_PROMPT` → SSE `event: advice`，ADR-18，Phase 4；
  CORE-02：`ChatEntry` 深夜+极端情绪 → `GuardrailRuleService.matchesEmotionBrake`，表 `guardrail_rule`（**V15**），ADR-6/ADR-18）。
  ⇒ 与 §2.4（两者已 ✅）**不再自相矛盾**。
- **顺带发现并修掉**：ADR-18/19 标题残留「（提议）」后缀，而正文早已写 `**状态**：✅ 已接受 + 已落地`。
  全仓仅这 2 条（后起的 ADR 改用 `**状态**` 行，不再挂标题后缀），且**无外部引用**带后缀标题 → 删后缀，正文状态行保留。

## ③ 补 §7.8 四类缺失用例 ✅ 已落地

`scripts/e2e_live.py` 从 **21 → 27 项**（CI 与本地同一套，ADR-68）；本地真跑 **27/27**：

| 用例 | 实测证据 |
|---|---|
| 创建对话：注册成功后出现在本人列表 | PASS |
| 删除会话：列表移除且详情不再返回正文（软删生效） | `delete_status=200, still_listed=false` |
| 跨用户删除会话被拒（归属校验） | ⚠️ **HTTP 200 + 体内 `code=403`**（不是 HTTP 403）|
| 赞踩反馈：`/evolution/vote` 受理，非法 voteType 被拒 | `vote=200 / invalid=400` |
| 情绪刹车片：深夜+极端词触发 4002，`continueBrake=true` 可继续 | 拿到刹车片原文 |

**新增发现（登记，未修）**：
- ⛔ **`/memory/message/{messageId}/feedback` 从 API 面够不着** —— `GET /memory/{cid}` 返回的是
  **Spring AI 的 `Message`**（不是持久化实体），响应里**没有 messageId**，全仓也**没有端点暴露**它；
  前端实际走的是 `/evolution/vote`（索引制）。⇒ 该端点只能用 DB 层/单测覆盖。
- ⚠️ 刹车片用例要求**深夜时段** → 评测装置强制窗口 0-24（`APP_EMOTION_BRAKE_START_HOUR/END_HOUR`，
  wrapper + CI 都设）。**这是装置值不是生产值**；已注明。

### ⛔ 本轮最贵的一课：宿主环境变了，看着像代码回归（白排查两轮）

连踩 4 轮"启动失败"，报 `Port 8088 was already in use`，而 **netstat 里 8088 干干净净**。
先错怪了自己刚加的 `APP_EMOTION_BRAKE_*`（撤掉重跑 → **仍失败** → 假设被证伪）。
真因：Windows **动态保留端口段** `8083-8182`（`netsh int ipv4 show excludedportrange protocol=tcp`），
**8088 正落在里面** → "没人 LISTENING、connect 也拒绝、就是 bind 不上"。实绑测试显示 **8088-8120 全不可绑**。
✅ 修法：装置启动前**真的试绑**一个端口（9000 起扫）交给 `SERVER_PORT`。
📌 **"没人 LISTENING" 只是"能绑"的必要条件，不是充分条件**（已写入 `docs/11` §七）。

## ④ 集成测试层（Testcontainers）✅ 已落地（ADR-69）

`docs/09` §3 里写了很久、从未落地的 testcontainers 层**已补齐**；实测 **9/9 通过、0 跳过**。

| 文件 | 断言（都是**具体契约**，不是"容器起来了"）|
|---|---|
| `src/test/java/.../it/MysqlMigrationIT.java`（6）| 迁移链幂等 · `scope` 默认 `BOTH` · `self_harm` **每行**皆 `INPUT`（ADR-55）· `emotion_brake_*` 是 L2 且启用（ADR-6）· `message.feedback` 默认 `NONE`（V6）· **正文逐字读回**（防 ADR-46 类字段错位）· NOT NULL 真在拦 |
| `src/test/java/.../it/PgVectorSchemaIT.java`（3）| 可建 `vector(1024)`+hnsw 余弦索引 · ⭐ **1023 维必须被拒**（维度漂移哨兵）· 余弦最近邻自洽 |

- 分层：`mvn test` 零外部依赖（单测 **367/367** 实测仍绿、surefire 不碰 IT）；IT 走 failsafe `verify`。
- CI 新增独立作业 `integration-test`（runner 自带 Docker，无需 secrets）。
- ⛔ **版本必须覆盖** `testcontainers.version=1.21.4`：BOM 的 1.20.6 与 Docker Desktop 4.82/引擎 29.6.1 不兼容
  → 探测失败 → **9 个 IT 被静默跳过而 `verify` 仍 BUILD SUCCESS**。📌 **"跳过"也会给出绿色。**
- ⛔ 首跑 2/9 失败，**两条都是我的测试假设错**（共享容器致"空库"前提不成立；`self_harm` 是每关键词一行而非一行）
  → 修的是**断言**，不是实现。📌 **新写的断言，其前提本身也是待验证的断言。**

**未做（如实留白）**：Spring 上下文级 IT（真实 mapper 映射）· Redis/MCP/降级链 · E2E 未改容器化。

## D. 建议顺序（①②③④ 已完成）

1. **B1-1 收敛 E2E**（小、直接消除"两套门"）
2. **A2 修 SRS §7**（小，但属"文档与代码偏离"，AGENTS.md §1 明令禁止）
3. **A3 补 4 类缺失用例**（补的是用例，不是功能）
4. **C1 集成测试层（Testcontainers）**（中，收益最大）
5. **C4 覆盖率 + C5 评测收敛**（中）
6. **A1 的 2 项 🚧（硬删/清空 + 删除口径）** —— 需要先定**产品口径**（软删算不算删除？）
7. C7 静态检查、C3 契约测试

## ⛔ 本审计的边界（不夸大）

- **未逐项跑 SRS 的 14 项**：本轮是**静态核对**（读代码/读文档），不是逐项验收。
  ⛔ 因此 §A1 的"其余 12 项 ✅"**只是未见反例**，不等于"已验证通过"。
- 只核了 `docs/11-软件需求规格说明书.md` 与 `docs/09-测试策略.md` 两份；`docs/01/05/06` 未逐条比对。
- 未评估前端测试（`front/`）；记忆里挂着"前端 5 个既有测试失败"未复跑。
