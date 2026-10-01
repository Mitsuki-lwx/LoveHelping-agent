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

## ⑤ 覆盖率 ✅ 已落地 / 评测收敛 ⛔ 建议**不做**（有证据）

### C4 JaCoCo ✅
- pom 加 `jacoco-maven-plugin`（prepare-agent / report / **check 在 verify**）；CI 的 integration-test 作业改跑 `mvn verify`。
- **先测基线再定门槛**：实测 **总体行覆盖 38.8%**（2408/6207）、指令 40.6%、核心 `harness.governance` **48.7%**。
- ⛔ `docs/09` §1 原写的"行 ≥70%、核心 ≥85%"**从未测过也未达到** → 门槛改为**钉在 0.38 的棘轮**（防退化，非达标线）。
- 实测：`mvn verify` 全绿（单测 367 + IT 9/9 不跳过 + jacoco:check 通过）。

### C5 评测收敛到 Langfuse：**改为旁路 sink（方案 A）**，已落地

**证据**：
1. `scripts/answer_eval.py` 首行写着「**路线 B——不依赖 Langfuse 配置链**」，`docs/09` §5.6 同样口径
   → "不用 Langfuse"是**刻意设计**，不是没做。
2. 它**已有稳定基线与实战价值**：AC=**0.90**（n=16×3 轮），且"评测驱动修复闭环已实战三次"
   （冷静期补年份 0.5→1.0、煤气灯文档拆分、QueryRewriter 关闭）。
3. 今天实测到 **Langfuse 会挂/会丢**：应用侧 OTLP 导出在装置里连续报 `HttpExporter: Failed to export`
   （本轮 E 断言的真凶）→ 把**唯一的质量回归手段**绑到一个**会挂的外部服务**上，是净负收益。

**✅ 拍板：选 A 并已落地**（ADR-70，`scripts/langfuse_sink.py`）。
路线 B 仍是主线；同一批**真实**分数额外推 Langfuse dataset `answer-correctness`（默认开，`--no-langfuse` 关）。
实测：16 条真条目 API 读回一致；真跑 `answer_eval.py` 得 `items=2 run_items=2 scores=2 回读 2 failed=0`（含真实 judge 理由与 traceId）；
死主机 2.0s 放弃（fail-fast）。
⛔ 途中三条实测教训（都已写进 ADR-70）：**`scores` 只带 `datasetRunId` 会 200 后静默丢弃**；
**`traceId` 与 `datasetRunId` 互斥**；**score 异步入库**，回读要有界重试 + 按本批 trace 比对。

## ⑥ 删除语义（口径已拍板）✅ 已落地

**审计发现比"缺个端点"严重**：ADR-5 承诺的三步里，**后两步从未实现** ——
`物理清除` 全仓只出现在 `Message.java` 一句注释里；全仓**没有任何删向量的代码路径**。
⇒"删除权"（《个保法》§47）长期只停在软删标记层。

**拍板与落地**：

| 项 | 决定 | 实现 |
|---|---|---|
| 注销时**级联删向量** | ✅ 现在就补 | `DeleteService` 调 `RetentionPurge.purgeUserMemoryVectors`（只删 `source='memory'` 且 `metadata.userId` 匹配） |
| 软删消息的**定期物理清除** | ✅ 现在补最小版 | `MessagePurgeScheduler`（尊重总闸 `scheduler.master-enabled`；保留期 `app.retention.deleted-message-days` 默认 30 天） |
| FR-COMM-08 清空全部历史 | ⛔ **不做** | 口径=**逐会话清空**（现有 `DELETE /memory/{cid}` 前端循环即可）；不新增高破坏面端点 |

**验证**：`RetentionPurgeIT`（真 MySQL + 真 pgvector，ADR-69）**4/4** ——
只删"软删**且**过期"的（未软删/未过期一条不动）· 别人的向量与共享知识库一条不动 · 空 userId 直接拒 · 重复跑幂等。
全量 `mvn verify`：单测 367 + IT 13/13（0 跳过）+ 覆盖率门槛通过；E2E 27/27。

**登记未做（如实留白）**：
- ⛔ **残留向量无重试**：PG 不可用时删向量只 ERROR 日志、不阻断注销（账号已禁用，主诉求已达成）→ 需人工/后续补重试。
- ⛔ **Skill 豁免的"用户同意"没有落地字段**：向量侧只删 `memory`（`evolution` 侧是经审核脱敏的共享产物），
  与 ADR-5"仅当用户明确同意匿名贡献且审核脱敏才豁免"的**原意一致**，但"是否同意"目前无处可查。

## ⑦ 静态检查（C7）✅ 已落地（ADR-71）

SpotBugs 接入 `mvn verify`。**先测基线再定门槛**（与覆盖率同一套纪律）：

| 项 | 实测 |
|---|---|
| 首次测量（Max/Medium）| **149 条**：High **10** / Medium 139 |
| 噪声 | **95 条 `EI_EXPOSE_REP2`** = Spring 把注入 bean 存进字段 → **框架常态，不是缺陷** |
| 门禁 | 只设 **High**（`failOnError=true`）；豁免清单 `spotbugs-exclude.xml` **逐条写理由、精确到类+方法+模式** |
| 修掉 | 3 条 High：`SiliconFlowEmbeddingModel` ×2（null 传给非空参数 → 改 `new EmptyUsage()`）、
`GoldenSetRunner`（`new String(byte[])` → 显式 UTF-8） |
| 保留并登记 | 5 条 `DE_MIGHT_IGNORE`（刻意的加固）+ 2 条**已知未修**结构性缺陷（见下） |
| ⛔ 活性验证 | 临时撤掉一条豁免 → `BugInstance size is 1` → **BUILD FAILURE** ✅（**证明门不是恒真**）|

**已登记、未修**：`ChatExecutor.SYSTEM_PROMPT` 5662 字符且**在另外 3 个 class 文件里重复**（提示词副本会漂移）；
`ScopeWording` 实例方法写 static（刻意，但同类曾因装配顺序静默失效，ADR-53）。

## D. 建议顺序（①–⑦ 已完成；余下为登记项）

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


---

## 余下登记项（未做，按价值排）

| # | 项 | 为什么还没做 |
|---|---|---|
| ~~R1~~ | ✅ **已做**（ADR-73）：实测发现"4 处副本"实为**四档对照臂各自的抬头**（本就不该合并）⇒ **不做集中化**，改加**漂移检测**进 CI，并证明门会响 | — |
| ~~R2~~ | ✅ **已做**（ADR-74）：新增 `GET /memory/{cid}/messages` 每条带 messageId（归属校验与旧端点一致），端点不再够不着 | — |
| ~~R3~~ | ✅ **已做**（ADR-75）：`VectorRescanScheduler` 每 10 分钟按批补扫**已注销账号**的残留向量（不新增状态字段、幂等、尊重总闸）| — |
| **R4** | **Skill 豁免的"用户同意"无落地字段**（ADR-5 原文要求）| ⏸ **Mitsuki 尚未想好**（2026-10-01）—— 维持现状并登记，不擅自加同意字段 |
| R5 | **覆盖率提到 docs/09 §1 原设想（行 70% / 核心 85%）** | 进行中：38.8% → **46.1%**（单测 367 → **442**）。策略=**先补"最该测却没测"的类**，不追数字；提门槛前不许改门假装达标 |
| ~~R6~~ | ✅ **已做**（ADR-72）：`scripts/check_api_contract.py` 进 CI，首跑抓到 1 条真漂移 | — |
| ~~R7~~ | ✅ **已做**（见下）| — |


---

## R7 前端测试 ✅（2026-10-01 补做）

**复现**：`npm test` → **24/29，5 条红**（2 个文件），与早前记录一致。

**归因**：5 条全是**描述旧 UI**，不是回归 ——
Home 页已改版为 4 张「信」主题入口卡（`/love-chat` `/sandbox` `/memory` `/history`），
品牌改为 `LOVEHELPING · 恋爱解忧所`（英文**大写**）；旧断言写的是 `LoveHelping` + `.card` 两张 + `manus-chat`。
Login 同理（英文 → 中文产品名）。

**处置**：按**当前真实契约**重写 ——
- 品牌只判**结构 + 中文产品名**（不再钉英文大小写这种排版细节）；
- 入口卡改为**导航映射契约**（点第 N 张 → 第 N 个路由），改路由就该红；
- Login 判 `.login-title` 存在 + 当前产品名 + 表单两个输入。

⇒ **27/27 全绿**。

⛔ **同时发现的真缺口**：**CI 完全不碰前端**（无 node/npm 步骤）——
而 `sseEventRouting.test.js` 守的是"`event:status` 不得被拼进正文"那条**真踩过的坑**
（前端是手写 fetch 解析，凡 `data:` 行都当正文）。**没有 CI 的门 = 没有门。**
⇒ 新增 CI 作业 `frontend-test`（`npm ci` + `npm test`），并**验证门会响**：
注入一条失败断言 → `npm test` 退出码 **1**；恢复后 27/27。

📌 **5 个永久红测试比没有测试更糟** —— 它们把人训练成忽略整个套件。


---

## R6 契约漂移检查 ✅（ADR-72）

`scripts/check_api_contract.py`：`docs/05` 的端点声明 ↔ 控制器映射，MISSING → 退出码 1，接入 CI。

**首跑即抓到真漂移**：`docs/05` 把 `GET /Love_app/chat/sse/tools` 列为"现状实现"，
而**全仓只有这一行提到它** —— 从未实现（工具调用实际由 `/sse/rag` 与 `/LoveManus` 承担）。已更正。

⛔ 造这个装置时我**连着制造了三个假信号**（详见 ADR-72）：正则漏掉带 `produces` 的映射（假漂移）、
可选段按"都要在"判定（假 MISSING）、文档里引用漂移端点反被当成新声明。
⇒ 门活性用**临时文档**验证（假声明 → exit 1），不动真文档。


---

## R5 覆盖率（进行中）—— 先补"最该测却没测"的

不追数字，按**未覆盖行最多且含真实契约**挑目标。首个目标：
`SiliconFlowEmbeddingModel`（**0%**，122 行，却在 RAG 主路径上）。

新增 `SiliconFlowEmbeddingModelTest`（8 条，用 JDK 自带 `HttpServer` 打桩，不引依赖/不需网络）：
`dimensions()==1024`（与 `vector(1024)` 绑定）· 非法 baseUrl 构造期即抛 · `embed(null)` 不糊 NPE ·
**空请求 usage 非 null**（ADR-71 那处修复的直接验证）· 缺 API Key 带排障指引 · 正常 1024 维取回 ·
⛔ **上游给 512 维必须抛**（"宁可失败暴露，也不写入错维度向量污染库"）· 非 200 必须抛。

### ⭐ 这批测试**当场抓到一个真 bug**（3 处同款）

原实现：`Set.of("http","https").contains(url.getScheme())` —— URL **漏了 scheme** 时
`getScheme()` 为 null → `ImmutableCollections$Set12.contains(null)` **抛 NPE**，
那句本想给出的 `IllegalArgumentException("Invalid ... URL")` **永远到不了**。
⇒ 用户拿到的是**空指针栈**，而不是"你 URL 写错了"。

三处同款，全部修掉 + 加回归测试（断言 **IAE 而非 NPE**）：
`SiliconFlowEmbeddingModel` · `LocalDocumentReranker` · `LangfuseTracingConfig`。

📌 这正是"0% 覆盖的关键路径"该被补的理由：**契约写在注释里，但没人执行过它**。
（`LocalDocumentRerankerTest` 里我第一版要求"必须是我的文案"—— 但 `127.0.0.1:8000/x`
在 `URI.create` 就抛 IAE（文案不同）⇒ 按输入区分断言，才是有区分力的测试。）


### R5 第二批：`SentimentService`（113 行 / 0%）+ `GuardrailAdvisor`（60 行 / 0%）

挑法：**确定性逻辑 + 明确协议**，且**注释里自己承认过风险**的类优先 ——
比"未覆盖行多"更值得测。

**`SentimentServiceTest`（6 条）**：空输入不落库不调上游 · 已有记录跳过（**不再多调一次 LLM**）·
Jev 命中用它的分值且**完全不走 LLM** · Jev 未命中回落 LLM 并解析 `[SCORE]/[WHY]`（含 trim）·
⛔ **输出格式跑偏 → 静默回落 0（=「平静」）** —— 代码注释自己写着这是"伪装成正常数据"的错误，
现在它被钉住了：将来改解析逻辑时这条会红并提醒你 · 上游异常不抛出（增强不是硬依赖）。
本轮**未发现新缺陷**（该类契约成立）。

**`GuardrailAdvisorTest`（6 条，伦理红线）**：全系统唯一"用户可见行为由规则决定"的地方，此前**一行没测**。
L3+`self_harm` → **转介文案（含热线 400-161-9995）**+记 BLOCKED+**不调下游** ·
L3+其他规则 → **通用阻断文案**（不误给心理热线）· L1/L2 → **放行**只记 LOGGED（提示级不能变阻断）·
⛔ **输出侧命中必须替换模型原文**（2026-09-05 的中危修复：此前仅记日志，"最后防线"形同虚设 —— 最易被重构悄悄改回）·
输出干净原样透出 · `order == MAX_VALUE`。

**结果**：单测 376 → **388**，覆盖率 40.2% → **41.5%**；`mvn verify` 全绿（IT 20/20、spotbugs 0）。
本轮**只加测试、未动生产代码** ⇒ 无需复跑 E2E。


### R5 第三批：`MemoryStore.retrieveAsContext`（201 行未覆盖 / 0.5%）

**`MemoryStoreRetrieveAsContextTest`（5 条）** —— 这段决定"给模型的记忆长什么样"：
空 userId → 空串且**一次都不查库** · 完全没记忆 → 空串（不是空标签壳子）·
事实渲染 + **命中计数与时间必须真写回**（排序依赖它）·
⛔ **必须带 ADR-14 防注入声明**（少了这句，用户写进记忆的文本就等于直接写进系统提示词）·
摘要/关系档案各自成段。

⭐ **抓到一处真实不一致**：`stage` 判 `!isBlank()`，而 `keyPeople`/`alerts` **只挡 null 与字面量 `"null"`**
→ 纯空白值会渲染出 `- 关键人物：   ` 这样一行**空标签塞进模型提示词**。已按作者本意统一（三处判据一致）。

**结果**：单测 388 → **393**，覆盖率 41.5% → **42.2%**；`mvn verify` 全绿；E2E **27/27**（改了生产代码，按 §0 复跑记忆路径）。


### R5 第四批：`MessageChatMemory`（70 行 / 0%）—— 补的是 **docs/09 §2 点名却没做**的用例

§2 把「**历史窗口裁剪（20/50 边界）**」列为单元测试重点，而该类此前**一行没测**。

**`MessageChatMemoryTest`（10 条）**：
⛔ **窗口 LIMIT 必须下推成 SQL 里的 LIMIT**（取回再裁会把整段历史读进内存）·
⛔ **顺序必须翻回「旧→新」**（SQL 是 `id DESC`，喂模型的必须是时间序 —— 这处改错**不报任何错**，
只会让模型看到倒着放的对话）· role→消息类型映射（含小写归一化）· ⛔ **anonymous 完全无状态**（不落库不读取，
隐私承诺不是优化）· 落库前**必须加密 + 算 HMAC** + 带 promptVersion · 空白正文跳过 ·
单行插入失败不影响其余 · `clear` 是软删 · 读取异常返回空表。

**本批未发现生产缺陷**，但踩到并记录了一条**测试基建坑**：
MyBatis-Plus 的 lambda wrapper（`Message::getXxx`）依赖 **TableInfo 缓存**，纯单测里没有 → 构造 wrapper 即抛
→ 而 `clear()` 自带 try/catch **把它吞了** ⇒ 测试看到的是"**零交互**"这种极迷惑的现象。
修法：`TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Message.class)`
（生产里由 mapper 扫描完成）。

**结果**：单测 393 → **403**，覆盖率 42.2% → **43.3%**；`mvn verify` 全绿。本轮只加测试 ⇒ 无需复跑 E2E。


### R5 第五批：RRF 融合（`docs/09` §2 点名的「RRF 融合排序稳定性」）

`ParentChildRrfFusionTest`（5 条）—— 混合召回 = 向量 topN + 关键词 topN → RRF 融合：
**去重**（两个通道都召回的同一条块只占一个位）· **加权**（双通道命中必须排在同秩单通道之前，
否则混合退化成并集）· ⛔ **先过滤、后截断**（ADR-40 的旧 bug：顺序反了会让记忆块白占候选位，
极端情况下重排在聊天链路上被**静默跳过**）· 关键词通道同样过知识库过滤 ·
**中文分词**（jieba 切出「冷战」「筑墙」且不含单字，否则 LIKE 近乎空转）。

⚠️ 顺带说明覆盖率为何一直没覆盖到：`app.rag.hybrid-search.enabled` **默认 false**，
这条路径平时不走（ADR 结论是重排为负收益、保持关闭）—— 但**开关一旦被打开**，
上面这些契约就得是对的，所以现在先钉住。

**结果**：单测 403 → **408**，覆盖率 43.3% → **43.5%**；`mvn verify` 全绿。只加测试 ⇒ 无需复跑 E2E。


### R5 第六批：`OutputGuardrail`（14.3%）——「最后防线」的**双向**判据

`OutputGuardrailTest`（5 条）。两层检查都钉了**两个方向**（只测一个方向的门是假门）：
- ⛔ 用户提到自伤、回复里**没有任何危机应答** → 必须拦（reason=`missing_crisis_response`，且给危机文案）；
- ⛔ **反向**：回复里已含「热线/专业/求助/心理」任一 → **不许拦**
  （误拦的代价：真正有用的危机回复被换成模板话术 —— 那是最坏的一种"安全"）；
- 输出含有害建议（报复/动手/以牙还牙/跟踪）→ 拦（reason=`harmful_advice`）；
- 空输出、普通对话 → 放行。

**结果**：单测 408 → **413**，覆盖率 43.5% → **43.8%**；`mvn verify` 全绿。只加测试 ⇒ 无需复跑 E2E。


---

## 专项核查：**流式路径的输出侧护栏**（Mitsuki「可以先查」）

**问题**：`GuardrailAdvisor.adviseStream` 里输出侧命中**只记日志、不替换**（而 call 路径 2026-09-05 已修为"必须替换"）。
是有意取舍还是漏网？

### 查清的三层事实

1. **那条日志调用是结构限制，不是漏写**：`ChatClientMessageAggregator` 在**流结束**时回调，
   那时文本已经送达用户，改不了。
2. **真正的逐块拦截在别处**：`StreamRegistry.StreamSink` —— 按 `guardrailRules.check(text, Scope.OUTPUT)` 判定，
   命中即**丢弃该块 + 改推替换文案**（含跨 chunk 的尾部窗口，`ChatEntry` 据此避免重复推）。
   它有测试且覆盖率高（`StreamRegistry` 89.5% / `StreamSink` 89.0%）。
3. **但两条路径的判据覆盖不同**（此前只有类注释一句含糊的"仅告警不阻断生成"）：

| 判据 | 非流式 `adviseCall` | 流式 |
|---|---|---|
| DB 规则 L3（`Scope.OUTPUT`）| ✅ 替换 | ✅ **逐块拦**（StreamSink）|
| `missing_crisis_response` | ✅ 替换 | ⚠️ **只能事后告警** —— 需"整段输出 + 用户输入"才判得出，**结构上做不到** |
| `harmful_advice`（纯文本关键词）| ✅ 替换 | ⛔ **目前没拦** ← **两路径不对称，疑似缺口** |

### 已做（零风险部分）
- 纠偏 `GuardrailAdvisor` 类注释（"命中仅告警不阻断生成"与 call 路径现状不符）；
- 在 `adviseStream` 就地写明**为什么只能告警**、**真正的拦截在哪**、**覆盖差异**；
- 补流式三条测试（L3 只发转介且**不订阅下游**、L2 放行、**事后命中只告警**——最后这条**故意钉住现状**，
  将来若做"事后追发纠正"会先撞上它，提醒那是**有意的行为变更**）。

### ✅ 拍板与落地：把 `harmful_advice` **搬进规则表**（Scope=OUTPUT）

理由：① 回到 **ADR-6「规则外置（DB 表，非硬编码）」**（原实现本就偏离）；② `StreamSink` 自动覆盖流式；
③ 两路径不再有第二套真相。

- **V30 迁移**：7 条关键词入 `guardrail_rule`（`rule_id=harmful_advice`, `level=3`, `scope=OUTPUT`）。
  选 OUTPUT 而非 BOTH：这些词针对**助手回复**，不该拿去拦用户输入（用户说"我想报复他"是在倾诉）。
- **`OutputGuardrail`**：删掉硬编码层（clean cutover），只留"危机应答缺失"那层（规则表达不了）。
- **`adviseCall`**：输出侧改查 `ruleService.check(text, Scope.OUTPUT)`，与流式 StreamSink **同一份规则**；
  文案走 `GuardrailMessages.forRule`（新增 harmful_advice 专属文案，与迁移前逐字一致 ⇒ 行为不变）。
- ⭐ **顺带修正**：原先命中时**一律用通用 BLOCK_TEXT**，把本层自带文案丢了 ——
  而"用户提到自伤、回复缺危机资源"最该给**援助热线**（`CRISIS_FALLBACK` 就为此存在）。已改用本层 fallback。

**证据链**：Flyway `Migrating to version "30"` ✅ · 启动日志 `OUTPUT-only=10`（**3 → 10**，正好 +7）✅ ·
单测 420/420 · IT 20+1（V30 契约）· E2E **27/27** + 附加断言 18/18。


---

## 前端重做（2026-10-01，Leonxlnx/taste-skill · Mitsuki 指示）

**任务**：用 taste-skill 重做前端，"要有独特的风格，不能千篇一律，要依照人机交互准则，要让用户看到想用"。

**Design Read（skill §0.B）**：情感陪伴 C 端 · 深夜倾诉场景 → **深色「晚灯书房」**氛围；
酒红保留为唯一强调色（灯光/印章/寄出的信）。dials = VARIANCE 8 / MOTION 5 / DENSITY 3。

### 审计发现的三个结构性问题（skill §11.B）
1. **全站只有亮色** —— 对"深夜倾诉"产品是氛围错配（同类竞品夜间情感场景几乎全是深色）；
2. **顶层导航脱节** —— App.vue 还用旧变量名 + 通用样式，代码里自己标注"过渡期"；
3. **首页等宽 4 卡阵列** —— 正是 skill §9.C 明令禁止的"等宽功能卡"形态。

### 落地内容
- **设计系统 v2（style.css 重写）**：OKLCH 深棕桌面（非纯黑）+ 顶部酒红灯晕 + 暖纸信卡
  （深色里的唯一亮面 = 视觉焦点）+ **单一强调色锁** + 圆角体系锁（大面 12px/胶囊 full）
  + 全动效 `prefers-reduced-motion` 兜底 + **旧变量映射**（其余视图零改动落深色）。
- **导航重做（App.vue）**：桌沿墨线 + 楷体印章品牌位 + 墨线分隔 + 酒红当前位（小印）
  + 窄屏横向滚动不换行（skill：导航必须单行）。
- **首页**：等宽 4 卡 → **不对称 2×2 便签墙**（主卡跨两列 + 火漆加大），破"AI 模板感"。
- **登录页**：深色中一张暖纸信卡（唯一焦点）；a11y 补齐：label 关联 / autocomplete / role=alert。
- **LoveChat 换肤**：信头/输入区深木、AI 回信=暖纸深墨、用户气泡=酒红渐变（深底提亮）、
  横格线压暗；顺带删掉一处 **em-dash**（skill §9.G 全禁）。
- **emoji 降权**（skill §3.D）：聊天交互里保留产品语境内少量 emoji，纯装饰位不再加。

### 浏览器实测（真实注册 → 登录 → 首页 → 寄信 → 流式回复）
- 注册-登录闭环 ✅；首页主卡跨两列（gridColumn "1 / -1"）✅；导航 56px 单行 ✅；
- 寄信 → AI 流式回复到达 ✅；**气泡颜色实测抓到一个真缺陷**：
  AI 回信是暖纸底，但正文颜色漏了 `color: var(--ink-on-paper)` → 深色主题下"暖白字浮在暖纸上"不可读。
  已修 + 实测复核：`aiColor = oklch(0.32 0.03 62)`（深墨）on `aiBg = oklch(0.93 0.012 80)`（暖纸）✅。
- ⚠️ 装置坑（复现 docs/11 那条）：`spring-boot:run` 加载 **target/classes**，
  改前端后必须 `mvn resources:resources`（或重启），否则浏览器测到的是旧产物——本轮为此白测一轮。

### 验证
`vite build` ✅ · 前端测试 **27/27** ✅ · 浏览器端到端（注册/登录/导航/寄信/流式回复/颜色）✅。
后端零改动（纯前端 + 主题层），故未复跑 mvn verify 与 E2E。


### R5 第七批：Agent 状态机 + 沟通分析（§2 最后一个点名项 + 一条伦理红线）

**`AgentTaskServiceTest`（11 条）** —— §2 点名的「**Agent 状态机非法迁移拒绝**」，此前 1.2%：
幂等键**复用同一任务**（防重复执行/扣费）· PENDING 起步 + 租户固定 default + 心跳已写 ·
⛔ **终态不回退**（SUCCESS/FAILED 再 cancel、SUCCESS 再 fail 都不动 —— 否则用户看到"已完成"变"已失败"）·
错误信息**截断 500 字**（DB 列宽）· 崩溃补偿把超时未心跳的标 FAILED(TIMEOUT) · 任务不存在静默返回。
⛔ 我第一版把 `cancel` 的守卫断言写成 `times(1)` —— 实际 **SUCCESS || FAILED 都拒**，两次都是 no-op；
断言写错会让人以为"FAILED 还能被取消"。**改断言，不是改实现。**

**`InsightServiceTest`（11 条）** —— 此前 0%，一次补两条本仓最在意的线：
- **伦理红线**（SRS §5.3）：⛔ 输出含「障碍/人格/诊断/依恋/焦虑症/抑郁症」→ 结果挂 `warning`
  （prompt 里禁了不等于模型听话，输出侧必须有）；干净输出**不挂**（别误伤）；永远附 disclaimer。
- **归属校验**（docs/07 §3）：⛔ 读/删**别人的**分析记录 → 403 且**不删任何东西**；不存在 → 404。
- 工程契约：模型把 JSON 包在 ```json 围栏里也要能剥出来（剥不出就整轮 500）；超长输入截断 4000 + 留痕。

⛔ 自身错误两处（已记）：① 按**字段**猜构造器签名（实际没有 ObjectMapper 形参）② `Prompt.getContents()`
在本版返回 `String`（我按 List 写了 `.get(0)`）—— **猜 API 与猜数据形状是同一类错误**。

**结果**：单测 416 → **442**，覆盖率 44.1% → **46.1%**；`mvn verify` 全绿（IT 20/20、spotbugs 0 High）。
只加测试 ⇒ 无需复跑 E2E。


---

## R1/R2/R3 落地（2026-10-01，ADR-73/74/75）

| 项 | 做法 | 验证 |
|---|---|---|
| **R1** | ⛔ **不做提示词集中化**（实测：所谓"4 处副本"是**四档对照臂各自的抬头**，本就不该合并 —— 抽象化会让灰度对照臂不可比）；改加 `scripts/check_prompt_drift.py` 进 CI：重复副本检测 + **分档结构契约**（六片段齐全 / SYSTEM_PROMPT 仍三段拼成 / 四档措辞齐全）| 两次注入实验都 **exit 1** 并准确点名（改档名 → 报"缺档位"；造副本 → 列出两份位置）|
| **R2** | 新增 `GET /memory/{cid}/messages`（每条带 messageId），归属校验与旧端点一致；旧端点不动 | `MessageHistoryIdsIT`（真 MySQL）5 条：id 是**真主键**且能定位到行 · 软删不出现 · ⭐ 与旧端点**同一批行同一顺序** · 空会话空列表 |
| **R3** | `VectorRescanScheduler` 每 10 分钟按批补扫**已注销账号**（判据只用 `users.enabled=false`，**不新增状态字段**；幂等、限量、尊重总闸、单账号失败不中断）| `VectorRescanIT`（真 MySQL + 真 pgvector，跨两库）5 条：只认已注销账号 · 该用户向量真被删 · ⛔ **别人与共享知识库向量一条不动** · 幂等 · 无注销账号时不打扰 |

**R4** ⏸ 按 Mitsuki 决定暂缓（未想好），维持现状并登记。

全量：`mvn verify` 单测 **442/442** · IT **31 条全过、0 跳过** · spotbugs 0 High · E2E **27/27** + 附加断言全 PASS。
