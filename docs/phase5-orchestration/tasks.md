# tasks.md — 业务编排图（OrchestrationGraph）实现任务

> **前置**：ADR-19（已立）。依赖 graph-core（StateGraph，现成依赖）。
> 原则：每步可编译可独立验证；删除旧代码与迁移新代码同一 Phase（AGENTS.md）。
> 参考：`AgentLoopExecutor` 的 ReactAgent 装配方式（RedisSaver/interceptors）、`ChatExecutor` 的提示词与上下文注入、`SandboxService.buildSandboxPrompt`、graph-core `StateGraph.addNode/addEdge/addConditionalEdges/compile(CompileConfig)`。

---

## Task 1：图骨架装配（可空节点运行）

**目标**：`OrchestrationGraph` 图可构建、可跑通空节点链路（START→END）。
**依赖**：无
**影响文件**：
- 新增 `infrastructure/orchestration/graph/OrchestrationGraph.java`（图定义）
- 新增 `infrastructure/orchestration/graph/GraphStateKeys.java`（状态字段常量）
- 新增 `infrastructure/orchestration/graph/GraphHooks.java`（hook 接口，先空实现）
- `pom.xml` 不需要（graph-core 已在）

**内容**：
- StateGraph 装配：start → 简单判定（条件边占位）→ 类型路由（条件边占位）→ 各处理节点（先空实现返回原状态）→ 统一检查 → END
- compile 时带 CompileConfig（RedisSaver checkpoint + 空拦截器，从 AgentLoopExecutor 迁移 saver 构建）
- 用 `addConditionalEdges(node, AsyncEdgeAction, Map<条件→目标节点>)` 表达两个判定
- 验收：图 `compile()` 成功；JMH 不用——写一个极简 Runnable 测试直接 invoke 空链路通过

## Task 2：简单问题判定规则

**目标**：`isSimpleQuestion(message)` 纯规则判定"直接回答"。
**依赖**：无（可与 Task 1 并行）
**影响文件**：
- `CapabilityRouter.java`（新增方法，与 needTools/isAdviceRequest 并列）
- 新增 `CapabilityRouterTest` 用例

**内容**：
- 正例：问候（"你好/嗨/在吗"）、纯情绪短句（"我今天很难过"）、已闭环短问答（"谢谢"）；负例：含工具意图、含话术请求、含检索词（"怎么/查一下/帮我"）、沙盘会话请求（带 sandbox 上下文由路由判断，不在本法内）
- 规则清单写死在方法内（参考现有 isAdviceRequest 风格），正负例 ≥ 各 5 条单测
- 与 isAdviceRequest/needTools 的优先级互斥关系单测（简单问题绝不同时是工具意图）

## Task 3：普通节点 + 回答节点（简）

**目标**：把 ChatExecutor 的普通对话逻辑移入图节点，行为不变。
**依赖**：Task 1
**影响文件**：
- 新增 `graph/node/NormalChatNode.java`（普通回复：记忆注入 + 知识库上下文 + LlmGateway 调用 + 话术三级激活 + advice 事件切片）
- 新增 `graph/node/QuickAnswerNode.java`（简单问题直答：同一生成管线，无话术/无检索）
- `ChatExecutor.java`（内容迁移或改造为普通节点内部 helper；保留 execute 供 GoldenSetRunner 等既有调用——见 Task 9 决定去留）

**内容**：
- NormalChatNode 输出 = 现 ChatExecutor.execute 的等价物（复用 SYSTEM_PROMPT/ADVICE_ACTIVATE/切片逻辑）
- QuickAnswerNode 用精简 system prompt（不注入三牌指令、不检索知识库，只带记忆）
- verify：同一输入走旧 ChatExecutor 与新节点输出结构一致（advice 事件仍在）

## Task 4：检查节点（护栏 + 话术激活 + 质量骨架）

**目标**：入口护栏移入图内统一检查语义，最终回复复检。
**依赖**：Task 1
**影响文件**：
- 新增 `graph/node/CheckNode.java`
- `ChatEntry.java`（入口护栏迁移；保留限流）
- `GuardrailRuleService`（新增最终回复复检语义：LLM 生成内容也过护栏，违规走降级文案）

**内容**：
- 生成前（入口）：L3 阻断 / 情绪刹车（现状逻辑原样迁入）
- 生成后（复检）：最终回复含 L3 词 → 降级为护栏转介文案（自伤/伤人）或婉拒（其余），记录 guardrail_event（content_hmac）
- 话术三级：是否已按 advice 请求产出三牌；未产出 → 标记前端可重试（不强制二次调用，成本考虑，记录 `advice.activated=0` 即可）

## Task 5：沙盘节点

**目标**：沙盘对话收编为图内模式节点，端点兼容。
**依赖**：Task 1、Task 3（普通生成管线复用）
**影响文件**：
- 新增 `graph/node/SandboxChatNode.java`
- `SandboxController.java`（`/sandbox/chat` 内部改走图；会话校验/记忆 CRUD 端点不动）
- `SandboxService`（buildSandboxPrompt 复用为节点输入）

**内容**：
- 进图条件：请求带 sandboxId（图入口识别 → 直接路由到沙盘节点，不经过简单判定/类型路由）
- 节点输出：sandboxPrompt（人格+记忆+动态情绪）+ RAG（Task 6 接入后生效）+ 沙盘专属上下文
- 兼容：SSE 输出格式与现 `/sandbox/chat` 一致（纯文本流）

## Task 6：工具循环（LLM ↔ 工具）

**目标**：图内实现 LLM↔工具条件边循环，ReactAgent 能力等价迁移。
**依赖**：Task 1、Task 3
**影响文件**：
- 新增 `graph/node/AgentLlmNode.java`（复用/封装 agent-framework 的 AgentLlmNode，或自绘：ChatModel 调用 + tool_calls 解析）
- 新增 `graph/node/AgentToolNode.java`（复用 AgentToolNode：ToolCallback 执行 + 结果消息回填）
- `AgentLoopExecutor.java`（ReactAgent 退役；其 SSE 桥接/消息聚合/agent_task 回调迁移到图执行器）
- 新增 `graph/GraphRunner.java`（图执行门面：流式桥接 + agent_task 完成回调 + 记忆落库）

**内容**：
- 条件边：LLM 输出含 tool_calls → 工具节点 → 回 LLM；无 → 检查节点 → END
- 终止语义：all_tools_done 类终止工具（ReactAgent 现用）等价；步数上限 15 兜底 → 错误路径
- SSE：🔧 工具可视化事件保留
- agent_task 状态机：PENDING→RUNNING→SUCCESS/FAILED 回调接入 GraphRunner（从 ChatService 迁移接线）

## Task 7：RAG 正式接线

**目标**：RetrievalAugmentationAdvisor + QueryRewriter 真正装配到普通/沙盘节点（修复现状 gap）。
**依赖**：Task 3、Task 5
**影响文件**：
- 新增 `rag/RagAdvisorConfig.java`（RetrievalAugmentationAdvisor 构建：Qdrant/pgvector 检索 + QueryRewriter QueryTransformer，开关沿用 `app.rag.query-rewrite.enabled`）
- `NormalChatNode/SandboxChatNode`（advice 链接入：检索管线在 LLM 调用前执行，上下文注入 prompt）

**内容**：
- 先确认 HybridRetrievalService 门面能否直接供 advisor 用（不能则包一层 `Retriever` 适配）
- 沙盘/普通节点同频开启；SSE 不变
- 验证：问知识库覆盖的问题（如"非暴力沟通怎么说"）→ 回复含知识库内容（现 SkillRetriever 注入对比）

## Task 8：Hook 与可观测

**目标**：节点级 hook + 指标 + 图路径 trace。
**依赖**：Task 1-6
**影响文件**：
- 新增 `graph/hook/GraphNodeInterceptor.java`（pre/post enter/exit、工具前后、onSimple、onTypeRoute、onError）
- 新增 `graph/GraphMetrics.java`（`orchestration.node.duration{node,route}`、`orchestration.path` 计数、简单命中率、路由分布）
- `GraphRunner`（trace span：图路径节点序列日志 + 状态快照）
- 复用现有 AgentMetricsInterceptor 的计量思路（Micrometer + Prometheus）

**内容**：
- 每节点进出打点；工具执行前后打点；错误/超步数打点
- 图路径 trace：一次请求的节点序列作为日志行 + 指标维度
- 状态快照：简单判定/类型路由/工具循环收敛后关键字段摘要（脱敏）

## Task 9：入口收编 + 删除旧代码

**目标**：ChatEntry 路由职责移除，沙盘/普通/Agent 全部走图；ReactAgent 退役。
**依赖**：Task 1-8
**影响文件**：
- `ChatEntry.java`（改造为图门面：guardrailCheck + 限流 + 进图，或删除、Controller 直连 GraphRunner）
- `SandboxController.java`（走图）
- `AgentLoopExecutor.java`（删除 ReactAgent 用法；保留/迁移的 SSE 桥接与回调进 GraphRunner）
- `AiController.java`（调整调用链，SSE 协议不变）
- `ChatExecutor.java`（删除或降级为节点内部 helper——GoldenSetRunner 调用点同步）

**内容**：
- 删除面清单核对（AGENTS.md 不并行）：ChatEntry 路由、沙盘直连逻辑、ReactAgent
- 全量编译 + 既有冒烟 15/15（不做任何新行为断言，先证明无损）

## Task 10：单测（判定/路由/循环收敛）

**目标**：关键规则与图行为单测。
**依赖**：Task 2、Task 6
**影响文件**：
- `CapabilityRouterTest`（isSimpleQuestion 正负例 + 与其他判定互斥）
- 新增 `graph/GraphLoopTest.java`（mock ChatModel/ToolCallback：有工具调用→回 LLM，无→END；步数超限→错误路径；checkpoint 不破坏收敛）

## Task 11：接入主流程 + 文档同步

**目标**：图成为线上唯一入口，文档状态推进。
**依赖**：Task 9
**影响文件**：
- `docs/03-技术决策记录.md`（ADR-19 状态 → 已接受+已落地）
- `docs/02-架构设计.md`（编排章节改写：单图结构图）
- `docs/06-核心流程设计.md`（聊天管道/Agent/沙盘章节指向图）
- `docs/phase5-orchestration/checklist.md`（勾选）
- `docs/10-重构路线图.md`（如路线图新增 Phase 5 条目则补充）

## Task 12：端到端验证

**目标**：既有冒烟全绿 + 图路径新断言 + 沙盘 RAG 用例。
**依赖**：Task 9-11
**影响文件**：
- `scripts/e2e-smoke.sh`（新增 7.10 编排图段：简单请求图路径=2 节点、Agent 请求经过工具循环、沙盘带 RAG 回复、advice 事件仍在 7.9 保留）

**验证内容**：
- 冒烟总断言：原 15/15 保持（7.1-7.9）→ 加 7.10
- 图路径断言：简单问候走 最短路径；工具请求 trace 到工具循环；错误路径（触发 L3）图内兜底
- 沙盘：带知识库问题的沙盘回复引用检索内容；沙盘记忆注入仍生效
- 性能：不比现状慢（节点/路由开销可忽略，指标里比对）