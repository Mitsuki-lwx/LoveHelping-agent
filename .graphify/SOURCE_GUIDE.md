# lwx-ai-agent 源码导览（2026-09-06）

> 155 个 Java 文件 / 17,013 行 / 649 个方法 / 501 条依赖边 / 18 张表 / 11 个 agent 工具（本地 4 + MCP 6 + terminate）
> 交互版：`code-map.html`（含架构 SVG 图与模块搜索）

---

## 0. 一句话主线

**ChatExecutor 是唯一汇聚点**——它把 SYSTEM_PROMPT + 记忆块 `<user_memory>` + 技能块【已学经验】+ RAG 检索上下文拼起来交给 LLM，并通过 MessageChatMemoryAdvisor 把对话落回 `message` 表，构成「落库 → 萃取 → 召回 → 注入」的闭环。

---

## 1. 请求主链路

```
AiController（5 个聊天端点）
  → TenantInterceptor（JWT → TenantContext；匿名放行、字段为 null）
  → ChatEntry 三道闸：prompt-leak 探测 → 护栏 L3 → 限流 → 在线并发闸门
  → GraphRunner.runAsync（恢复父 trace，90s orTimeout）
  → OrchestrationGraph.classify（优先级：offTopic > forceAgent > sandbox > vision > simple > needTools > normal）
  → 分支节点：
      quick_answer / normal → ChatExecutor（带 RAG advisor）
      vision → GraphVisionNode；sandbox → SandboxChatNode
      agent → AgentLlmNode ⇄ AgentToolNode（≤15 步，🔧 实时推）
      off_topic → OffTopicNode 固定话术（不调 LLM）
  → CheckNode（输出复检，L3 替换文案）
  → StreamRegistry 增量回传 SSE → complete 时释放闸门
```

**设计要点**：所有聊天端点（含 `/LoveManus`）收敛到 ChatEntry 单一入口，护栏/限流/闸门/trace 只做一次。唯一例外是沙盘 `/sandbox/chat`（直调 GraphRunner，已补 L3 护栏）。

---

## 2. 模块职责

| 包 | 一句话职责 | 核心类 |
|---|---|---|
| `controller` | HTTP 入口；聊天全转 ChatEntry | AiController、AuthController（注册强制 USER）、MemoryController（带归属校验）|
| `orchestration` | 编排中枢：入口 + 状态图路由 + 真流式 | ChatEntry、ChatExecutor、OrchestrationGraph、GraphRunner、CapabilityRouter、StreamRegistry、各节点类 |
| `ai` | 模型网关（重试/降级/埋点）与视觉 | LlmGateway(@Primary ChatModel)、VisionChatClient |
| `harness` | 治理护栏：规则拦截优先于模型遵从性 | GuardrailRuleService、GuardrailAdvisor、OutputGuardrail |
| `rag` | Spring AI RAG 管线（advisor 契约）| RagAdvisorConfig、DocumentRetriever（overlap + RRF）、Transformer、rerank/* |
| `retrieval` | 手写检索门面（不经 advisor）| HybridRetrievalService（pg_trgm + 向量 → RRF）|
| `memory` | 长期记忆闭环 | MessageChatMemory、MemoryExtractor、MemoryStore、MemoryVectorStore、ExtractionScheduler |
| `evolution` | 自我进化：从对话沉淀话术方法论 | ReflectionScheduler、SkillReflector、SkillIngestor、SkillRetriever |
| `service` | 业务服务 | AgentTaskService、SandboxService、EvolutionService、RateLimiter、DeleteService |
| `tenant` | 多租户与鉴权 | TenantFilter/Interceptor、JwtTokenProvider、AdminGuard(fail-closed) |
| `tools` + `mcp-server` | 工具能力（运行时合并）| 本地 4 + MCP 6（搜索/抓页/图片/天气/约会）|
| `observability`/`scheduler` | 运行时治理 | OnlineLoadTracker、SchedulerBudget(fail-open)、LangfuseReporter |
| `admin`/`canary` | 运营支撑 | AdminController（评测依赖 /rag/retrieve）、GoldenSetRunner、CanaryInterceptor |
| `entity`/`mapper` | 数据层（MyBatis-Plus）| 17 实体 / 18 表 / 4 个自定义 SQL mapper |

---

## 3. 记忆闭环（记住用户是谁）

```
对话落库（加密 + HMAC + prompt_version → message）
  → 每 30min：调度器挑「有归属 + 有消息 + 无摘要」会话
  → MemoryExtractor LLM 抽取摘要与事实（画像必抽 / 建议禁抽 / confidence 8-9）
  → 摘要 → conversation_summary（覆盖更新 + 向量化）
    事实 → user_memory（≥7 分 ACTIVE，否则 CANDIDATE）
  → 下次对话 retrieveAsContext 注入 system prompt
  → 用户编辑即转正（confidence=10）；清除会话连坐删摘要与事实
```

| 表 | 角色 |
|---|---|
| `message` | 原始逐条消息真源（加密、软删、带 feedback/prompt_version）|
| `conversation_summary` | 会话级压缩摘要（跨会话上下文 + 向量召回源）|
| `user_memory` | 用户级跨会话稳定事实（category/confidence/status/hit_count/ttl，用户可改）|

---

## 4. 进化闭环（学会怎么说话）

```
每 5min：ReflectionScheduler 挑「未反思」且（空闲超时 或 总时长超限）会话
  → SkillReflector 结合点赞/点踩：赞→正面经验，踩→「Avoid…」教训，过滤 qualityScore
  → SkillIngestor 以 PENDING 落库（不自动向量化）；无产出插 skip-mark 防重复烧配额
  → 审核通过 → vectorize 写入 pgvector
  → SkillRetriever 只留 APPROVED → 拼【已学经验】注入 prompt
```

**记忆 vs 进化**：记忆 = 关于**用户**的事实（user 维度、用户可改、有 ttl）；进化 = 关于**AI 该怎么说**的方法论（tenant 维度、LLM 产出、人工审核、按语义召回）。

---

## 5. RAG 为什么有两套

| | `rag/`（生成侧管线）| `retrieval/`（工具侧门面）|
|---|---|---|
| 契约 | Spring AI DocumentRetriever/Transformer/PostProcessor | 普通方法，无框架契约 |
| 调用方 | RetrievalAugmentationAdvisor 管线内 | KnowledgeSearchTool、SkillRetriever、MemoryVectorStore |
| 服务场景 | normal/sandbox 节点上下文注入 | agent 工具、技能检索、记忆语义检索 |
| 策略 | overlap 切块 + 查询改写 + jieba + RRF + LLM 重排 | pg_trgm 整句相似度 + 向量 → RRF（无改写/重排）|

同一张 `vector_store` 表上的历史双轨。**改检索时务必确认改的是哪一条**。

---

## 6. 已知死代码（勿误用）

- `AgentRegistry` / `AgentDefinition`：ADR-19 遗留，无业务消费者
- `AgentMetricsInterceptor` / `AgentGuardrailInterceptor`：ReactAgent 移除后无装配点
- `MyKeywordEnricher` / `MyTokenTextSplitter`：历史遗留
- `FileOperationTool` / `TerminalOperationTool`：多租户下等于开放宿主机，已摘除注册
- `SessionTracker`：仅打 DEBUG 日志，触发逻辑已迁走
- `SandboxDriftDetector.onDrift`：全库无调用方（漂移机制未接线）
- `RetrieverType` Javadoc 仍描述已移除的 Milvus + ES

---

## 7. 推荐阅读路线

1. 跑通一次请求：AiController → ChatEntry → classify → NormalChatNode → ChatExecutor → LlmGateway
2. 路由决策：CapabilityRouter 四个判定 + classifyInner 优先级链
3. 上下文来源：ChatExecutor.assembleContext（记忆 + 技能 + RAG）
4. 后台闭环：MemoryExtractionScheduler(30min) 与 ReflectionScheduler(5min)
5. 治理：harness 护栏三件套 + SchedulerBudget + OnlineLoadTracker

---

*图谱数据 `.graphify/main-graph.json`（graphify 静态分析，import 依赖边）· 本导览随代码演进需同步更新*
