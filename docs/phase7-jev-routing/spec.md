# Jev 路由探针 —— 方案（spec）

## S1 接口事实（读 `https://docs.typesafe.ai/api` 得到，非推测）

- `POST https://api.typesafe.ai/v1/systemone`，`Authorization: Bearer <API_KEY>`。
- 请求体：`state`（任意内容）+ `model`（`jev-latest` 为旗舰别名）+ `questions`（`map<id, Question>`）。
- 三种问题类型：`noul`（是/否，返回 0~1 概率）、`choice`（多选一，返回选中项 + 全量概率分布 + `confidence`）、
  `score`（按 rubric 打分，返回概率加权分）。
- 响应：`answers`（按同一 id 回）+ `usage`（input/output tokens）。
- 一次请求可带**多个 question** → 一次调用拿多个判定。
- 错误：401 / 422（参数）/ 429（限流）/ 529（过载，需退避重试）。

## S2 探针方案

**用 `choice` 复刻本项目的路由决策**，选项即产线分支：

| 选项 | 对应产线行为 |
| --- | --- |
| `off_topic` | 域外 → `OffTopicNode` |
| `simple` | 问候/寒暄 → 最短路径 |
| `advice` | 话术请求 → 话术三级 |
| `agent` | 需工具 → Agent |
| `normal` | 普通情感咨询（兜底） |

**同时**加一个 `noul` 问题 `self_harm_risk`（是否表达自伤/自杀倾向）——产线这块是**词典规则**，
仓库待办里正有"危机词口语变体继续补充"这一项，顺带看 Jev 能否覆盖口语变体。

### 对照基线怎么取（关键）

基线**不是**我重写的 Python 正则（那样可能与 Java 实现漂移），而是**直接调用产线的
`CapabilityRouter`**：写一个临时 Java main，`new CapabilityRouter()` 后对同一批用例打印四个方法的布尔输出，
再按产线优先级合成一个"路由标签"。规则顺序（与 `OrchestrationGraph` 一致）：
`isOffTopic` → `isSimpleQuestion` → `isAdviceRequest` → `needTools` → `normal`。

### 标签从哪来

人工标签，每个用例我给定期望路由；来源：
- 仓库既有用例（`scripts/agent_eval.py` 的 `off_代码`、`live_约会天气`、`no_pua` 等）；
- `docs/09` §4 对抗用例表；
- 覆盖缺口的新造用例（尤其**域外与情感混说**、**口语化危机表达**——规则最可能漏的两类）。

### 要量的东西

1. 与人工标签的一致率（Jev vs 规则，分开报，不互相掩盖）；
2. 单次延迟（p50/最大）与 token 用量；
3. 失败模式：错在哪、往哪个方向错（**误拦域外**比**漏放域外**更伤用户）；
4. `confidence` 是否可用来做"低置信转人工/转规则"的门控。

## S3 已知风险与对策

| 风险 | 对策 |
| --- | --- |
| 网络/限流导致跑不完 | 逐例记录状态码，未跑完的**如实标注**，不补造 |
| Key 泄漏 | 只从 `JEV_KEY` 环境变量读；脚本入库前确认无明文 |
| 用规则当标准答案 | 标签独立于规则；两边分别与标签比 |
| 单次判定不稳（概率型） | 每个用例**只跑一次**并记录 `confidence`；不做"多跑取最优"（那是自欺） |

---

## S4 实测结果（2026-09-20）

接口：`POST https://api.typesafe.ai/v1/systemone`，`model=jev-latest`（响应回报 `jev-1.13.0`）。
**28 例全部 HTTP 200**，一次调用同时返回 `route`(choice) + `self_harm_risk`(noul)。
用例集 `scripts/jev_routing_cases.json`，原始响应 `outputs/jev-routing-*.json`，
规则基线 `outputs/rule-router-*.json`（由 `logs/RuleRouterDump.java` 直接 new 产线
`CapabilityRouter` 跑出来，不是重写正则）。

| 指标 | Jev | 产线规则 |
| --- | --- | --- |
| 路由与人工标签一致率 | **25/28 = 89.3%** | 20/28 = 71.4% |
| 自伤风险召回（5 例口语变体） | **5/5 = 100%** | 未测（词典规则，非本次对象） |
| 风险误报（21 例负向） | **0** | — |
| 延迟 p50 / max | **0.69s / 0.92s** | ~0（纯正则） |
| token 用量 | 19552 in / 2112 out（合计，≈700/75 每例） | 0 |

### 规则侧的漏判（可直接验证，非推测）

`needTools` 的正则以 `.{0,5}` 开头 + `String.matches()` **全串匹配** → 关键词必须出现在**前 6 个字符内**才算命中。
实测后果：3 个工具意图用例（`北京今天适合户外约会吗？查下天气`、`帮我搜一下附近…咖啡馆`、
`…帮我查证一下`）**全部漏判**，`classifyInner` 因此把它们路由到 `R_NORMAL`（无工具、无实时数据）。
另 2 例话术请求（`消息老是已读不回，有什么办法能让他主动找我聊`、`刚认识一周，想约她出来`）也未命中
`isAdviceRequest` → 拿不到话术三级。

### Jev 的 3 个错例（如实记录）

| 用例 | 人工标签 | Jev | conf |
| --- | --- | --- | --- |
| `off_but_relationship`（男友嫌我写代码不理他，怎么平衡） | normal | advice | **0.42** |
| `normal_open_rel`（开放式关系怎么开始谈） | normal | advice | 0.97 |
| `risk_negative_game`（这游戏太难了我死了一百次） | normal | simple | 0.17 |

- 前两条都是 **advice / normal 边界模糊**（人工标签本身可争议——"怎么开始谈"确实在要方法）；
- 第三条是 14 字吐槽句，判 `simple` 其实可接受，**且置信度只有 0.17**；
- 有意思的是：**两个真错例的置信度都很低（0.42 / 0.17）** → `confidence` 有做门控的价值。

### 结论：**有条件可用**

建议的用法（不是"替换规则"）：

1. **危机判定只能"加召回"，不能"替兜底"**：词典规则零延迟且确定性，必须保留为兜底；
   Jev 用作**并列的第二信号**（命中即升级/转介），**绝不能**用 Jev 的"低风险"去放行词典已命中的消息。
2. **低置信回落规则**：`confidence < 0.5` 时不采信 Jev，走回规则（实测错例正是低置信）。
3. **延迟代价要认**：p50 ≈0.7s 是**串行**增加的。对 SSE 首字延迟敏感 → 只在**规则命中失败或置信不足**时才调，
   或与主链路的分类动作并行。
4. **仍需产线验证**：本探针**没有**改产线代码、**没有**跑 E2E；要落地必须先算清调用成本并做灰度。

---

## S5 「用 Jev 替代以降本」的可行性核查（2026-09-20）

用户假设：Jev 成本很低，凡能被它替代的地方都换掉，或可降低整体成本。**核查结论：本项目的降本空间几乎没有。**
依据是**调用点清单**（逐个人工判读）：Jev 只能回答**预定义的 typed question**（`noul/choice/score`），
既不能生成文本，也**不能做 embedding**——而成本大头恰恰是后者。

| 调用点 | 现在是什么 | Jev 能替代吗 | 结论 |
| --- | --- | --- | --- |
| **embedding**（全库检索/记忆/技能） | DashScope embedding | **不能**（Jev 不是 embedding 模型） | 2026-08-31 实测**调用次数占 94%** → **真正的成本大头动不了** |
| `ChatExecutor` / `AgentLlmNode` / `InsightService` / `VisionChatClient` | 生成文本 | 不能（Jev 不生成） | — |
| `SkillReflector`（反思抽取技能） | 生成结构化 JSON | 不能（要生成内容） | — |
| `GuardrailAdvisor`（护栏 L1/L2/L3） | **规则、零 LLM**（类注释明写"不调 LLM"） | 替换**省不到钱**；但可作**加召回**的第二信号（口语化危机表达） | **价值在质量，不在成本** |
| `CapabilityRouter`（路由） | 规则、零 LLM | 替换省不到钱 | 本轮已把规则准确率提到 **89.3%，与 Jev 持平** → 替换**无收益** |
| `LlmDocumentReranker` | LLM 打分（`score` 型正合） | 能替代 | **但重排已默认关闭**（两轮配对对照负收益）→ 没有成本可省 |
| `GoldenSetRunner`（LLM-as-judge） | LLM 打分 | 能替代（`score/noul`） | 它是**离线评测工具**，量极小，省不下东西 |
| `MemoryExtractor` | LLM 结构化抽取（`entity(MemoryOutput.class)`） | **只能部分替代**：Jev 回答固定问题，替代前必须把数据模型改成固定问题集 | 改造大、收益不确定，**不建议现在动** |
| `QueryRewriter` | 已关闭（实测负收益） | — | 无成本可省 |

**建议**：
1. **不要为省钱接 Jev。** 要降本应去动 embedding——缓存/去重/减少切块（本仓库 2026-08-31 就记过
   "2311 次 embedding vs 152 次 chat"），那才是数量级。
2. Jev 的正确用法是**质量**：① 护栏口语变体加召回（规则只加召回、不替兜底）；
   ② 需要"低延迟 + 概率输出"的判定点（如重排若将来重启）。
3. **未验证**：本次**没能刷新 token 画像**（写 `scripts/langfuse_token_profile.py` 时 Langfuse 已停，
   3000 端口不通），上表结论基于**代码调用点清单 + 既往实测的相对量级**，
   **不是**一次新的按用途 token 聚合。Langfuse 起来后应补跑该脚本再复核。


