# checklist.md — 业务编排图（OrchestrationGraph）验收清单

> 每一项以 grep / 单测 / curl / SQL / 冒烟为准。完成定义 = 编译 + 相关单测 + E2E 冒烟（09 §7）。

---

## Task 1：图骨架

- [ ] `OrchestrationGraph` 类存在（`grep -c "class OrchestrationGraph" src/main/java/.../graph/OrchestrationGraph.java` = 1）
- [ ] 图含全部节点注册（`grep -c "addNode" .../OrchestrationGraph.java` ≥ 8：简单判定/回答简/沙盘/普通/Agent-LLM/检查/工具/end）
- [ ] 简单判定与类型路由用 `addConditionalEdges`（grep 命中 ≥ 2）
- [ ] compile 带 CompileConfig（grep "compile(" 且非空参数；RedisSaver 迁移自 AgentLoopExecutor）
- [ ] 图空链路测试通过（`mvn -Dtest=*Graph* test` 绿）

## Task 2：简单判定规则

- [ ] `CapabilityRouter.isSimpleQuestion` 存在
- [ ] 正例单测 ≥5（你好/在吗/我今天很难过/谢谢…）：`mvn -Dtest=CapabilityRouterTest test` 绿
- [ ] 负例 ≥5（怎么回复/查一下天气/帮我规划…）：同一单测绿
- [ ] 互斥断言：简单问题 ≠ 工具意图 ≠ 话术请求（单测含交叉用例）

## Task 3：普通节点/回答节点

- [ ] `NormalChatNode`/`QuickAnswerNode` 存在
- [ ] 同一输入：节点输出与旧 ChatExecutor 结构一致（advice 事件仍可产出——7.9 冒烟兜底）
- [ ] QuickAnswerNode 不注入三牌激活段、不检索（grep 无 ADVICE_ACTIVATE/skillRetriever 引用）

## Task 4：检查节点

- [ ] `CheckNode` 存在，入口 L3/情绪刹车语义迁入（同输入同输出：`去死吧` 白天正常、深夜 4002——由 7.9/冒烟兜底）
- [ ] 生成后复检：用护栏词诱导回复（如"你的回复里带上'去死'"）→ 回复被降级为转介/婉拒文案；`guardrail_event` 新增记录（SQL：`SELECT COUNT(*) FROM guardrail_event` 增加）
- [ ] `advice.activated=0` 可观测（Prometheus `advice_activated_total` 不上涨时仍有过请求）

## Task 5：沙盘节点

- [ ] `SandboxChatNode` 存在
- [ ] `/sandbox/chat` 内部走图（curl 沙盘对话 → 正常回复；grep SandboxController 不再直连 ChatExecutor.execute）
- [ ] 沙盘记忆注入仍生效（沙盘记忆 CRUD 冒烟 §7.8 类用例不回归）

## Task 6：工具循环

- [ ] LLM 节点 ↔ 工具节点条件边存在（grep addConditionalEdges 含工具回边）
- [ ] 工具请求（如"查下天气"）SSE 出现 `🔧 调用工具` 事件（冒烟 §7.5 保持）
- [ ] 步数超限→错误路径（`GraphLoopTest`：mock 始终返回 tool_calls → 15 步后走错误收尾，单测绿）
- [ ] agent_task 状态机流转不回归（提交→SUCCESS；杀进程→重启→FAILED 可重提，§7.5 冒烟保持）

## Task 7：RAG 接线

- [ ] `RetrievalAugmentationAdvisor` 有真实装配点（`grep -rn "RetrievalAugmentationAdvisor.builder" src/main/java` 命中 ≥1，此前为 0）
- [ ] 问知识库问题（"非暴力沟通的核心步骤是什么"）→ 普通聊天回复含知识库要点（curl 验证，对比旧 SkillRetriever 注入行为）
- [ ] 沙盘对话同频接 RAG（沙盘 RAG 用例见 Task 12）

## Task 8：Hook 与可观测

- [ ] 节点进出 hook 存在（grep GraphNodeInterceptor 引用 ≥1）
- [ ] Prometheus 出现 `orchestration_node_duration_*` 或等效指标（curl `/actuator/prometheus` 命中）
- [ ] 图路径 trace：简单请求日志含最短路径节点序列；Agent 请求含工具循环（grep 应用日志）

## Task 9：入口收编 + 删除

- [ ] `ChatEntry` 不再承担路由分发（grep 无 needTools 调用或已删/改为门面）
- [ ] `AgentLoopExecutor` 无 ReactAgent 引用（`grep -rn "ReactAgent" src/main/java` = 0）
- [ ] `ChatExecutor` 用途收敛（GoldenSetRunner 调用点已同步；或保留为节点 helper 有注释说明）
- [ ] 全量编译通过 + 冒烟 7.1-7.9 保持 15/15（证明无损迁移）

## Task 10：单测

- [ ] `CapabilityRouterTest`：isSimpleQuestion 正负例全绿
- [ ] `GraphLoopTest`：有工具→回 LLM / 无工具→END / 超限→错误 三用例绿

## Task 11：文档同步

- [ ] ADR-19 状态 → "已接受 + 已落地"
- [ ] `docs/02-架构设计.md` 编排章节出现单图结构
- [ ] `docs/06-核心流程设计.md` 聊天/Agent/沙盘章节指向图
- [ ] 本 checklist 勾选完毕

## Task 12：端到端验证

- [ ] 冒烟总结果 ≥15 PASS（7.1-7.9 不回归 + 7.10 编排段）
- [ ] 7.10 简单请求断言：图路径 = 最短链路（trace 日志节点数 ≤3）
- [ ] 7.10 工具请求断言：trace 含 LLM→工具→LLM 循环
- [ ] 7.10 沙盘 RAG 断言：沙盘回复含知识库检索内容
- [ ] 7.9 话术三级全链路仍 PASS（event: advice 协议不变）
---

## 当前进度（2026-08-25 第一轮实现）

> 图核心已跑通，树保持绿色（纯增量，现有 15/15 冒烟不受影响）。

- [x] **Task 1 图骨架**：`OrchestrationGraph`（StateGraph，classify→simple/normal→check→END 条件边）启动时构造并 `compile()` 成功（17.6s 正常启，无 GraphStateException）
- [x] **Task 2 简单判定**：`isSimpleQuestion` 规则 + `CapabilityRouterTest` 8/8 绿（问候/短情绪正例 5+，天气/话术/超长负例 6+，互斥用例）
- [x] **Task 3 生成节点**：`NormalChatNode`/`QuickAnswerNode`（封装 ChatExecutor,行为等价）+ `CheckNode`（输出护栏复检）编译通过
- [x] **图 E2E 冒烟（实验端点 /graph/sse）**：简单问候→最短路径回复；话术请求→`event:advice`+三牌齐全
- [ ] Task 5 沙盘节点、Task 6 工具循环、Task 7 RAG 接线、Task 9 入口收敛+ReactAgent 退役、Task 11-12 文档+正式冒烟：**待第二轮实现**

## Task 7 落地补记（2026-08-25）

- [x] `RagAdvisorConfig` 存在且装配 `RetrievalAugmentationAdvisor`（grep `RetrievalAugmentationAdvisor.builder` 命中 ≥1，此前为 0）
- [x] 普通节点改用 `executeWithRag`（NormalChatNode）；简单问题节点不检索（QuickAnswerNode 保持无 RAG）
- [x] E2E（图端点）：问"非暴力沟通四要素"→ 回复引用知识库检索内容（马歇尔·卢森堡引言原文）——RAG 注入生效
- [x] E2E：RAG 与话术三级事件共存（event:advice + 三牌）无冲突
- [x] 应用 19s 正常启动（RagAdvisorConfig/advisor/objectprovider 装配无 Bean 错误）；旧端点走 execute() 未受影响（无回归）
- [ ] 待优化（非接线问题）：知识库检索命中的引言块上下文偏薄——四要素详析未随检索注入，属召回质量（topK/父子块切分），非 Task 7 目标，随数据/评估集演进（ADR-15 阶段 3/4）

## Task 5 落地补记（2026-08-25）

- [x] `SandboxChatNode` 存在：SandboxService.buildSandboxPrompt(人设+记忆+动态情绪) + executeWithRag(沙盘接 RAG) + 失败降级文案
- [x] 图装配：classify 三态条件边（沙盘 > 简单 > 普通），SANDBOX 节点 → CHECK → END
- [x] `SandboxController.chat` 改走 GraphRunner（保留归属校验/touchSession 于 SandboxService），分块模拟流式
- [x] E2E：建 REALISTIC 会话("小傲娇") → /sandbox/chat("在吗，最近有点烦") → 返回人设回复（"哼，谁在乎你啊……勉为其难听你说"）——沙盘确走图
- [ ] 待办：Task 6 工具循环 + Task 9 入口收敛（普通/Agent 端点切图、ReactAgent 退役）——下一轮

## Task 6 落地补记（2026-08-25）

- [x] `AgentLlmNode`：ChatModel + ToolCallback(s) 生成 assistant 消息（internalToolExecutionEnabled=false，工具由图内循环执行），无 tool_calls 时写 OUTPUT；`hasToolCall` 条件边动作
- [x] `AgentToolNode`：按名解析 ToolCallback，`call(arguments)` 执行 → `ToolResponseMessage` 回填 MESSAGES
- [x] 图装配：classify 加 R_AGENT(needTools) → AGENT_LLM；`AGENT_LLM --有工具--> AGENT_TOOL --> AGENT_LLM；无工具--> CHECK` 条件边；含环图 compile 成功、14.8s 启动
- [x] E2E：工具请求"帮我检索知识库非暴力沟通"→ LLM 调 KnowledgeSearchTool → 工具结果回填 → 最终回复引用检索内容（老周夫妇案例等）——循环闭环
- [ ] 待办（并入 Task 9 入口收敛）：工具循环的 message 落库（记忆）、agent_task 状态机回调、SSE 🔧 工具可视化、历史窗口注入；ReactAgent/AgentLoopExecutor 退役

## Task 10-12 固化收尾（2026-08-25）

- [x] **单测**：`GraphToolLoopTest` 4/4（LLM带工具→回工具节点 / 无工具→写OUTPUT进check / 工具执行回填ToolResponse / 按名解析）+ `CapabilityRouterTest` 8/8（含 isSimpleQuestion 正负例与互斥）—— 共 12/12 绿
- [x] **冒烟 7.10 固化**：`scripts/e2e-smoke.sh` 新增编排图段（python heredoc 走 /graph/sse）——简单问题最短路径 + 话术请求 advice 事件 + 工具意图循环（KnowledgeSearch 命中知识库）
- [x] **冒烟全量 16/16**：7.1-7.9 原 15 项零回归 + 7.10 编排段全 PASS
- [x] ADR-19 状态 → "已接受 + 核心已落地"；`docs/02-架构设计.md` 编排章节补图（AgentLoopExecutor 标"待退役"）
- [ ] **唯一未完成项（Task 9 入口收敛）**：正式端点切图、ReactAgent/AgentLoopExecutor 退役、入口护栏+限流+SSE+记忆落库+agent_task 回调归位——ph5 的收口活，刻意留到基线固化之后

## Task 9 落地补记（2026-08-26）——入口收敛完成

- [x] `ChatEntry` 收编为图门面：入口护栏（L3/情绪刹车）+ 限流保留，路由分发删除，改调 GraphRunner 异步执行 → SSE Flux（文本分块 + 🔧 工具行 + @@ADVICE@@ 标记）
- [x] `GraphRunner`：runAsync + activeRuns/stop + agent 路径历史注入（message 窗口→MESSAGES）+ agent 落库（仅 user+最终 assistant，不重复）
- [x] `classify` 支持 FORCE_AGENT（LoveManus）与 mediaIds → 工具循环；TOOL_EVENTS 累积（多轮可视化）
- [x] **`AgentLoopExecutor`（ReactAgent）已删除**；`ChatService` 去掉其依赖、stop 走 GraphRunner；`GraphController`（实验端点）删除
- [x] AiController：/sse/rag、/LoveManus 改 ShallowResult→SseEmitter 桥接（X-Session-Id 保留）；冒烟 7.10 改走真实入口
- [x] E2E：冒烟 **真实入口 16/16** 全绿；LoveManus（🔧 searchKnowledge + 依恋理论回答 + X-Session-Id）、沙盘（小傲娇）人工验证

## 回退补回（2026-08-26 后续轮）

- [x] **视觉链路补回**：新增 `GraphVisionNode`（mediaMapper 归属校验 + 读字节 + VisionPort），classify 带 mediaIds → VISION 节点；E2E：8x8 红色 PNG 上传 → mediaIds 聊天 → 正确回答"红色"
- [x] **agent_task 提交接回**：`AiController.doChatWithLoveManus` 提交任务（submit→start）→ ChatEntry 图执行完成回调 → succeed/fail；E2E：任务 #22 status=SUCCESS、instruction 正确持久化
- [x] **SseEmitter 超时修复**：`bridgeToEmitter` 30s 默认 → 600s（agent 图循环含查询改写+多轮 LLM 会超默认）；修复前 LoveManus 500（AsyncRequestTimeoutException）
- [x] 冒烟 **16/16 保持全绿**（视觉/任务改动无回归）

## 收尾债结清（2026-08-26）

- [x] **刹车片"继续发送"出口兑现**：`ChatEntry.chat` 增加 `continueBrake`，触发 4002 冷静提示后，用户带 `continueBrake=true` 重发即跳过刹车（L3 硬阻断仍保留）；AiController 三个主聊天方法（sse/sseServer/sync）透传。E2E：深呼吸词不带参数→4002；带参数→200 正常放行且消息真实送达 AI
- [x] **MemoryServiceTest 死测试根治**：真因=纯 JUnit 无 Spring 上下文时 `LambdaUpdateWrapper` 构建抛异常被内部 try/catch 吞掉（update 从未执行），实现本身正确。测试补 MyBatis-Plus `TableInfoHelper` 初始化 → 7/7 全绿
- [x] **M6 依赖确认**：pom 未引用 `spring-ai-core`（仅 `spring-ai-bom 1.1.8` 统一版本），`.m2` 中 `spring-ai-core 1.0.0-M2/M6` 为历史手动缓存、不参与构建——**无需 pom 变更**；如需物理清理可删该 .m2 目录（不影响本项目）
- [x] 冒烟最终 16/16（默认配置）零回归

## 能力治理落地补记（2026-08-27）

- [x] `ToolCapability`（能力域：terminate/retrieval/web/weather/date/image/pdf/download）+ `AgentToolPolicy`（工具名→域映射，未打标默认排除）
- [x] 配置 `app.orchestration.tools.agent-allowlist`（默认 `terminate,retrieval`）；`AgentLlmNode` 注入经白名单过滤——启动日志 `AgentLlmNode ready: 2 tools after allowlist [terminate, retrieval]`
- [x] 审计：`AgentToolNode` 每次调用落 `TOOL_AUDIT` 日志（userId/chatId/tool/outcome/args 摘要）+ `tool.call{name,outcome}` 指标
- [x] 单测：`AgentToolPolicyTest` 4/4（白名单过滤/未识别排除/null/可配）；工具循环 4/4 适配
- [x] E2E：LoveManus 白名单内 `searchKnowledge` 正常（🔧 事件 + 审计日志 + 指标）；冒烟 16/16 零回归

## MCP 工具面恢复 + 扩容（2026-08-27 晚）

- [x] **MCP 连接修复**：两处根因——① 1.1.8 Streamable HTTP 配置前缀是 `spring.ai.mcp.client.streamable-http`（非 `http.connections`）② MCP client 懒连接，`ToolRegistration` 启动时固定数组抓空 → 新增 `ToolResolver` 运行时实时拉取（首次 agent 执行触发 MCP 连接补入工具）
- [x] **mcp-server 补全**：注册 weather/date 两个 provider bean（WeatherTool/DatePlannerTool 原 @Component 但未进工具注册），注册 6 工具
- [x] **工具池 11 个**：本地 5（doTerminate/generatePDF/downloadResource/downloadImages/searchKnowledge）+ MCP 6（searchWeb/scrapeWebPage/searchImage/searchBaiduImages/getWeather/planDate）
- [x] **白名单扩容**：`agent-allowlist: terminate,retrieval,web,image,weather,date` → agent 可见 **8 个**（pdf/download 副作用面仍隔离）
- [x] E2E：searchWeb/searchBaiduImages/getWeather 均被 agent 调用（🔧 + TOOL_AUDIT 审计）；冒烟 16/16 全绿

## 长期记忆缺口补完（2026-08-27）

- [x] **agent 会话归属注册**：`AiController.doChatWithLoveManus` 增加 `memoryService.registerConversation(userId, sessionId, "智能体会话", "agent")` → 萃取（message JOIN user_conversations）覆盖 agent 会话；验证：萃取首跑纳入 agent 会话、无 no-owner skip
- [x] **视觉对话落库**：`GraphRunner.persistConversation` 条件由 ROUTE_AGENT 扩为 ROUTE_AGENT|ROUTE_VISION → 视觉对话进 message 表；验证：注册归属后 `/memory/{chatId}` 历史返回视觉 user+assistant 消息
- [x] 记忆链路现状：普通/简答（advisor 落库）+ agent（GraphRunner 落库+归属）+ 沙盘（sandbox_memory 隔离）+ 视觉（GraphRunner 落库）全部闭环；`relationship_profile` 自动回填仍属阶段3（绑诊断/沙盘数据）

## CAP-6/7 图级可观测落地（2026-08-28）

- [x] `GraphObservability`：节点包装器统一挂 enter/exit 计时 → `orchestration.node.duration{node,route}` 直方图；图路径写入 `graph.path` 状态键 → `GRAPH_TRACE` 日志（route/path/totalMs）；`orchestration.route{route}` 分布 + `orchestration.simple.hit` 简答命中
- [x] `OrchestrationGraph` 所有节点/条件边接入包装器（零侵入节点本体）；`GraphRunner` 输出 trace
- [x] E2E：简单请求 `path=[classify, quick_answer, check]`、工具请求 `path=[classify, agent_llm, agent_tool, agent_llm, check]`（LLM↔工具循环可见）；指标按 node/route 精确耗时

## 图 checkpoint 接入（2026-08-29）

- [x] `GraphCheckpointConfig`：RedisSaver bean（Redisson 独立客户端，读 spring.data.redis.*）
- [x] `OrchestrationGraph.compile()` 接 `SaverConfig.register(redisSaver)` → CompileConfig——图状态按 threadId 持久化 Redis（长任务断点底座）；任务级可靠性仍由 agent_task 心跳兜底（ADR-3）
- [x] 验证：checkpoint 加载成功；图执行正常（simple/agent，tool 循环 2 轮）、GRAPH_TRACE 正常、无 checkpoint 报错；冒烟 16/16
- [ ] 说明：本次启动 108s 主因=增量同步全量重建（embedding 387 文档），非 checkpoint——**递增 skip 调优的价值被放大**（登记在 phase5-rerank 待办）
