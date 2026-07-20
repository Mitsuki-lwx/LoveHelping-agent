# lwx-ai-agent 项目架构文档

> 日期: 2026-07-17  
> 框架: Spring Boot 3.4.4 + Spring AI 1.1.7  
> JDK: 21

---

## 一、项目概览

AI 情感助手（恋爱大师），支持多轮对话、RAG 知识库检索、工具调用、Agent 自主推理循环。

```
用户输入 → (可选 Agent 多步推理) → 检索知识库 → LLM 生成 → 展示给用户
```

---

## 二、技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3.4.4, Spring AI 1.1.7 |
| LLM | DashScope (deepseek-v4-pro) / DeepSeek (deepseek-v4-flash) |
| 向量库 | PGvector（默认）/ Milvus（可选 hybrid） |
| 数据库 | MySQL（对话记忆）/ PostgreSQL（pgvector） |
| 可观测性 | Actuator + Micrometer Tracing → OTLP → Langfuse |
| 工具 | @Tool 注解 + Spring AI MCP Client |
| 构建 | Maven, JDK 21 |

---

## 三、核心概念

### Agent（智能代理）

能自主思考 + 调用工具的 AI。不像普通聊天一问一答，Agent 可以在一次回答中多次调用 LLM 和工具，自主决定如何完成任务。

**Agent 循环**：
```
用户输入 → LLM 思考（think）
         → 决定调工具？→ 执行工具（act）→ 结果加回上下文 → 再次 think
         → 不调工具？→ 输出最终答案
```

### RAG（检索增强生成）

AI 回答问题前，先从一个知识库里搜索相关内容，再把搜到的内容 + 用户问题一起发给 AI，让它基于资料回答。好处：不瞎编、更专业、可回答私有知识。

### Tool（工具）

一段 Java 方法，Agent 可在对话中调用。如搜索网页、下载图片、生成 PDF。

### VectorStore（向量库）

把文字转成向量（数字数组），搜索时找"意思最接近"的文字。
- PGvector：基于 PostgreSQL 插件（当前默认）
- Milvus：专用向量数据库（新增备选）

### SSE（Server-Sent Events）

服务器流式推送数据给前端的技术，实现打字机效果。

### OTLP / OpenTelemetry

可观测性标准，收集程序的调用链数据（方法调用、耗时）。本项目通过 OTLP 把追踪数据发给 Langfuse。

---

## 四、包结构（共 43 个源文件）

```
src/main/java/cn/lwx/lwxaiagent/
├── LwxAiAgentApplication.java         # 启动入口
├── agent/                              # Agent 核心
│   ├── BaseAgent.java                  # 抽象基类：SseEmitter 流式执行引擎
│   ├── ReActAgent.java                 # ReAct 模式：think() + act() 编排
│   ├── ToolCallAgent.java              # 工具调用 Agent：LLM 决策 + 工具执行
│   ├── LoveManus.java                  # 具体 Agent 实现
│   └── model/AgentState.java           # 状态枚举 IDLE/RUNNING/FINISHED/ERROR
│
├── app/LoveApp.java                    # 业务逻辑入口，封装所有对话能力
│
├── controller/
│   ├── AiController.java               # REST API 端点
│   └── healthController.java           # 健康检查
│
├── config/
│   ├── ChatModelConfig.java            # 默认 ChatModel（deepseek-v4-flash）
│   ├── CorsConfig.java                 # 跨域配置
│   └── PgvectorProperties.java         # PGvector 连接配置
│
├── rag/                                # RAG 检索（原有）
│   ├── PgVectorVectorStoreConfig.java  # PGvector 初始化 + 文档加载
│   ├── LoveAppVectorStoreConfig.java   # SimpleVectorStore（已注释）
│   ├── LoveAppDocumentLoader.java      # 从 classpath:document/*.md 加载
│   ├── LoveAppRagCloudAdvisorConfig.java # 阿里云百炼 RAG
│   ├── LoveAppRagCustomAdvisorFactory.java # 自定义 Advisor 工厂
│   ├── LoveAppContextualQueryAugmenterFactory.java # 查询增强
│   ├── QueryRewriter.java              # LLM 查询改写
│   ├── MyKeywordEnricher.java          # 关键词增强
│   └── MyTokenTextSplitter.java        # Token 分割
│
├── retrieval/                          # 混合检索（新增，第一期）
│   ├── RetrieverType.java              # 枚举：pgvector / hybrid
│   ├── HybridRetrievalProperties.java  # 配置映射
│   ├── HybridRetrievalConfig.java      # @ConditionalOnProperty 配置切换
│   ├── MilvusVectorRetriever.java      # Milvus 客户端（连接/录入/搜索）
│   └── MilvusDocumentInitializer.java  # 文档初始化（仅空时导入）
│
├── tools/                              # 工具列表
│   ├── ToolRegistration.java           # 注册所有 @Tool 为 ToolCallback[]
│   ├── KnowledgeSearchTool.java        # 知识库搜索（Agent 调用的检索入口）
│   ├── WebSearchTool.java              # 通用网页搜索
│   ├── WebScrapingTool.java            # 网页抓取
│   ├── BaiduImageSearchTool.java       # 百度图片搜索
│   ├── FileOperationTool.java          # 文件读写
│   ├── TerminalOperationTool.java      # 终端命令
│   ├── PDFGenerationTool.java          # PDF 生成
│   ├── ResourceDownloadTool.java       # 资源下载
│   └── TerminateTool.java              # Agent 终止
│
├── advisor/MyLoggerAdvisor.java        # ChatClient 请求/响应日志
├── chatMemory/FileChatMemory.java      # 文件持久化记忆（未启用）
├── constant/FileConstant.java          # 文件路径常量
├── demo/...                            # 测试 demo
│
└── harness/observability/              # 可观测性（新增）
    ├── ObservabilityConfig.java        # OTLP 配置检查
    └── TracingAdvisor.java             # 对话级 Observation（可弃用）
```

---

## 五、核心流程详解

### 5.1 简单对话

请求 → `AiController.chatSync()` → `LoveApp.doChat()` → `ChatClient.prompt().user().call()` → 返回 String

### 5.2 流式对话（无工具）

请求 → `AiController.chatSse()` → `LoveApp.doChatByStream()` → `ChatClient.prompt().user().stream().content()` → 返回 Flux<String>

### 5.3 流式 RAG 对话（新增）

请求 → `AiController.chatSseWithRAG()` → `LoveApp.doChatByStreamWithRAG()` → `QuestionAnswerAdvisor`（PGvector 检索）+ `ChatClient.stream()` → 返回 Flux<String>

### 5.4 Agent 对话（LoveManus）

请求 → `AiController.doChatWithLoveManus()` → `LoveManus.runStream(message)`：

```
BaseAgent.runStream():
 ① 校验状态，加载 MySQL 历史
 ② 将用户消息加入 messageList
 ③ 循环（最多 15 步）:
    step() = think() + (如需) act()
    
    think():
      → messageList + 系统提示词 + 工具列表 → LLM
      → LLM 返回：思考内容 + 工具调用列表 / 最终答案
      → 有工具调用 → 返回 true（需要 act）
      → 无工具调用 → 返回 false（结束）
    
    act():
      → toolCallingManager.executeToolCalls()
      → 执行对应 @Tool 方法
      → 结果加入 messageList
      → 检查是否调了 doTerminate → 结束
 ④ 每次结果通过 SseEmitter 推给前端
 ⑤ 结束后保存对话到 MySQL
```

### 5.5 RAG 检索路径

**方式 A：通过 LoveApp**
```
LoveApp.doChatWithRAG() → QuestionAnswerAdvisor（PGvector）→ ChatClient.call()
```

**方式 B：通过 KnowledgeSearchTool（Agent 调用）**
```
Agent think() → 决定调 searchKnowledge → KnowledgeSearchTool.searchKnowledge()
  → rag.type=pgvector: PgVectorVectorStore.similaritySearch()
  → rag.type=hybrid:   MilvusVectorRetriever.search()
  → 格式化返回文本
```

### 5.6 混合检索架构（新增，rag.type=hybrid）

```
用户查询
     └──→ Milvus（向量语义检索）→ Top N
               ↓
          RRF 融合排序（k=60, topK=10）（待实现）
               ↓
          Top K 送入 LLM
```

---

## 六、每个类的详细说明

### 6.1 LwxAiAgentApplication.java

```java
@SpringBootApplication
public class LwxAiAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(LwxAiAgentApplication.class, args);
    }
}
```

启动入口。无业务逻辑。

---

### 6.2 agent/ 包 — Agent 核心

#### BaseAgent.java（341 行）

**职责**：所有 Agent 的抽象基类，提供 `runStream()` 流式执行引擎。

**关键属性**：

| 属性 | 类型 | 说明 |
|------|------|------|
| name | String | Agent 名称（如 "LoveManus"）|
| systemPrompt | String | 系统提示词，定义 AI 角色 |
| nextStepPrompt | String | 下一步提示，提醒 AI 可用工具 |
| state | AgentState | IDLE / RUNNING / FINISHED / ERROR |
| currentStep | int | 当前步骤号 |
| maxSteps | int | 最大步数（默认 15）|
| chatClient | ChatClient | 用于调用 LLM |
| messageList | List\<Message\> | 对话消息列表（核心数据）|
| chatMemory | ChatMemory | MySQL 持久化记忆 |
| conversationId | String | 对话 ID |

**方法**：

| 方法 | 说明 |
|------|------|
| `runStream(String userPrompt)` | 核心方法。创建 SseEmitter，异步执行 Agent 循环 |
| `stop()` | 外部停止 Agent，设置 state=FINISHED |
| `resetForNextTurn()` | 重置步数状态，保留 messageList |
| `step()` | 抽象方法，由 ReActAgent 实现 |
| `cleanup()` | 清理资源 |
| `extractLastThought()` | 提取最新 AI 推理内容（reasoningContent）|
| `extractLastText()` | 提取最新 AI 可视文本 |
| `isToolsCalled()` | 判断当前步骤是否调用了工具 |
| `extractFileOutput()` | 从工具结果提取文件 URL |
| `removeLocalPaths()` | 将本地路径转为 HTTP URL |
| `removeUrlLines()` | 过滤 URL 行和工具执行状态行 |

**runStream() 执行流程**：

```java
public SseEmitter runStream(String userPrompt) {
    SseEmitter emitter = new SseEmitter(600000L);        // 10分钟超时
    CompletableFuture.runAsync(() -> {                   // 异步执行
        if (state != IDIE) { emitter.send("错误"); return; }
        state = RUNNING;
        if (chatMemory != null && messageList.isEmpty()) // 加载 MySQL 历史
            messageList.addAll(0, chatMemory.get(conversationId));
        messageList.add(new UserMessage(userPrompt));    // 加用户消息
        for (int i = 0; i < maxSteps && state == RUNNING; i++) {
            String stepResult = step();                  // think + 可选 act
            String thought = extractLastThought();       // 提取推理内容
            boolean toolsCalled = isToolsCalled();
            if (toolsCalled && thought != null)
                emitter.send("💭 " + thought);           // 推推理过程
            else {
                emitter.send("✨ " + 最终答案);            // 推最终答案
                state = FINISHED;
            }
        }
        emitter.complete();
        if (chatMemory != null)                          // 保存到 MySQL
            chatMemory.add(conversationId, messageList);
    });
    return emitter;
}
```

#### ReActAgent.java（43 行）

**职责**：实现 ReAct（Reasoning + Acting）模式。

```java
public abstract class ReActAgent extends BaseAgent {
    public abstract boolean think();
    public abstract String act();

    @Override
    public String step() {
        boolean shouldAct = think();
        return shouldAct ? act() : "thinking completed, no action needed.";
    }
}
```

#### ToolCallAgent.java（137 行）

**职责**：实现 think() 和 act() 的具体逻辑。

**关键属性**：

| 属性 | 类型 | 说明 |
|------|------|------|
| toolCallingManager | ToolCallingManager | 工具调用管理器 |
| avilableTools | ToolCallback[] | 可用工具列表 |
| toolCallChatResponse | ChatResponse | 最近一次 LLM 响应 |
| ChatOptions | ChatOptions | 禁用内部工具执行 |

**方法**：

| 方法 | 说明 |
|------|------|
| `think()` | 把 messageList 发给 LLM，LLM 决定调不调工具 |
| `act()` | 执行 LLM 指定的工具调用，处理结果 |

**think() 详解**：

```java
public boolean think() {
    // 1. 首次运行时添加 nextStepPrompt
    if (nextStepPrompt 没加过) {
        messageList.add(new UserMessage(nextStepPrompt));
    }
    // 2. 调用 LLM
    ChatResponse response = chatClient.prompt(prompt)
        .system(systemPrompt)
        .toolCallbacks(avilableTools)
        .call()
        .chatResponse();
    // 3. 解析 LLM 是否想调工具
    List<ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
    this.toolCallChatResponse = response;
    return !toolCalls.isEmpty();  // true=需要调工具, false=直接回答
}
```

**act() 详解**：

```java
public String act() {
    // 执行工具调用
    ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
    // 更新上下文
    setMessageList(result.getConversationHistory());
    // 检查是否调了 doTerminate
    if (调用了 doTerminate) { state = FINISHED; }
    // 返回工具执行结果
    return "工具名: " + result;
}
```

#### LoveManus.java（36 行）

**职责**：项目中唯一的 Agent 实例，配置具体的提示词和 ChatClient。

```java
@Component
public class LoveManus extends ToolCallAgent {
    public LoveManus(ToolCallback[] tools, ChatModel model) {
        super(tools);
        this.setName("LoveManus");
        this.setSystemPrompt("You are LoveManus, a general-purpose AI assistant...");
        this.setNextStepPrompt("You have tools available... When done, call the terminate tool.");
        ChatClient cc = ChatClient.builder(model)
            .defaultAdvisors(new MyLoggerAdvisor()).build();
        this.setChatClient(cc);
    }
}
```

#### AgentState.java

```java
public enum AgentState { IDIE, RUNNING, FINISHED, ERROR }
```

---

### 6.3 app/LoveApp.java（221 行）

**职责**：封装所有对话能力，供 Controller 调用。

**系统提示词**：

```java
"扮演深耕恋爱心理领域的专家。开场向用户表明身份...适当在回复内容中穿插一些小图案或emoji...来增强氛围感"
```

**构造函数**：

```java
public LoveApp(ChatModel model, JdbcChatMemoryRepository repo) {
    ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(repo).maxMessages(10).build();
    chatClient = ChatClient.builder(model)
        .defaultSystem(SYSTEM_PROMPT)
        .defaultAdvisors(
            new MessageChatMemoryAdvisor(chatMemory),  // MySQL 记忆
            new MyLoggerAdvisor()                       // 日志
        )
        .build();
}
```

**方法**：

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `doChat()` | message, chatId | String | 同步简单对话 |
| `doChatByStream()` | message, chatId | Flux\<String\> | 流式简单对话 |
| `doChatWithRAG()` | message, chatId | String | 同步 RAG（PGvector）|
| `doChatByStreamWithRAG()` | message, chatId | Flux\<String\> | **新增** 流式 RAG |
| `doChatWithTools()` | message, chatId | String | 同步带工具对话 |
| `doChatByStreamWithTools()` | message, chatId | Flux\<String\> | 流式带工具 |
| `doChatWithMCP()` | message, chatId | String | 同步 MCP 工具对话 |
| `doChatWithReport()` | message, chatId | LoveReport | 结构化恋爱报告 |

---

### 6.4 controller/AiController.java（132 行）

**职责**：暴露 REST API。

| 端点 | 方法 | 说明 |
|------|------|------|
| `GET /Love_app/chat/sync` | `chatSync()` | 同步简单对话 |
| `GET /Love_app/chat/sse` | `chatSse()` | 流式对话 |
| `GET /Love_app/chat/sse/tools` | `chatSseWithTools()` | 流式 + 工具 |
| `GET /Love_app/chat/sse/rag` | `chatSseWithRAG()` | **新增** 流式 + RAG |
| `GET /Love_app/chat/LoveManus` | `doChatWithLoveManus()` | 🎯 Agent 对话（核心）|
| `GET /Love_app/chat/LoveManus/stop/{sessionId}` | `stopLoveManus()` | 停止 Agent |

**LoveManus 实例管理**：

```java
ConcurrentHashMap<String, LoveManus> activeSessions;  // sessionId → Agent

doChatWithLoveManus():
  sessionId 存在 → 复用 Agent（保留历史）
  sessionId 不存在 → 创建新 Agent，绑定 MySQL 记忆
  → loveManus.runStream(message) → 返回 SseEmitter
```

---

### 6.5 config/ 包

#### ChatModelConfig.java

```java
@Bean @Primary
public ChatModel primaryChatModel(@Qualifier("deepSeekChatModel") ChatModel cm) {
    return cm;  // 默认用 DeepSeek
}
```

#### PgvectorProperties.java

读取 `app.pgvector.datasource.*` 配置。

#### CorsConfig.java

跨域配置。

---

### 6.6 rag/ 包 — 知识库检索

#### PgVectorVectorStoreConfig.java（84 行）

**职责**：初始化 PGvector 向量库 + 文档加载。

```java
@Bean
public VectorStore PgVectorVectorStore(EmbeddingModel embeddingModel) {
    // 1. 连接 PostgreSQL
    // 2. 创建 PgVectorStore（COSINE_DISTANCE, HNSW 索引）
    // 3. 检查表中是否有数据
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", ...);
    if (count == 0) {
        // 4. 加载 Markdown 文档，每批 10 条写入
        List<Document> docs = loveAppDocumentLoader.loadMarkdowns();
        for (List<Document> batch : batches) vectorStore.add(batch);
    }
    return vectorStore;
}
```

#### LoveAppDocumentLoader.java（49 行）

从 `classpath:document/*.md` 加载 Markdown 文件，解析为 `Document` 对象。

#### LoveAppRagCloudAdvisorConfig.java

配置阿里云百炼 RAG（DashScopeDocumentRetriever）。

#### LoveAppRagCustomAdvisorFactory.java

自定义 Advisor 工厂，按状态（单身/恋爱/已婚）过滤。

#### LoveAppContextualQueryAugmenterFactory.java

查询增强器，给用户问题加上下文。

#### QueryRewriter.java

用 LLM 改写查询使其更适合检索。

#### MyKeywordEnricher.java / MyTokenTextSplitter.java

关键词增强 / Token 分割工具。

---

### 6.7 retrieval/ 包 — 混合检索（新增，第一期）

#### RetrieverType.java

```java
public enum RetrieverType { pgvector, hybrid }
```

#### HybridRetrievalProperties.java（51 行）

配置映射类：

```java
@ConfigurationProperties(prefix = "rag")
public class HybridRetrievalProperties {
    private RetrieverType type = RetrieverType.pgvector;
    private Hybrid hybrid = new Hybrid();

    // Hybrid → Milvus { host, port, collectionName, vectorDim }
    // Hybrid → Rrf { k, topK }
}
```

#### HybridRetrievalConfig.java

Spring 配置类，`rag.type=hybrid` 时创建 MilvusVectorRetriever Bean。

```java
@Configuration
@EnableConfigurationProperties(HybridRetrievalProperties.class)
public class HybridRetrievalConfig {
    @Bean
    @ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
    public MilvusVectorRetriever milvusVectorRetriever(...) { ... }
}
```

#### MilvusVectorRetriever.java（~250 行）

**职责**：与 Milvus 向量数据库交互的客户端。

**构造参数**：host, port, collectionName, vectorDim, EmbeddingModel

**方法**：

| 方法 | 说明 |
|------|------|
| `init()` | @PostConstruct。连接 Milvus，确保 collection 存在 |
| `close()` | @PreDestroy。关闭连接 |
| `ensureCollection()` | 检查 collection 是否存在，存在则加载，不存在则创建 |
| `recreateCollection()` | 删除重建 collection |
| `createCollection()` | 创建 collection（显式 schema + 索引）|
| `storeDocument(Document)` | 将文档嵌入向量后写入 Milvus |
| `search(String, int)` | 搜索最相似的 topK 条文档 |
| `isEmpty()` | 判断 collection 是否为空 |
| `deleteCollection()` | 删除整个 collection |

**createCollection() 详解**：

使用 Milvus v2 SDK 的显式 schema 模式（非动态字段）：

```java
var idField = FieldSchema.builder().name("id").dataType(Int64).isPrimaryKey(true).autoID(true).build();
var vectorField = FieldSchema.builder().name("vector").dataType(FloatVector).dimension(1024).build();
var textField = FieldSchema.builder().name("text").dataType(VarChar).maxLength(65535).build();
var metadataField = FieldSchema.builder().name("metadata").dataType(VarChar).maxLength(65535).build();

var schema = CollectionSchema.builder()
    .fieldSchemaList(List.of(idField, vectorField, textField, metadataField)).build();

client.createCollection(CreateCollectionReq.builder()
    .collectionName("love_knowledge")
    .collectionSchema(schema)
    .indexParams(List.of(IndexParam.builder().fieldName("vector").metricType(COSINE).build()))
    .build());
```

**search() 详解**：

```java
var queryVector = embeddingModel.embed(query);
var resp = client.search(SearchReq.builder()
    .collectionName(collectionName)
    .data(List.of(new FloatVec(queryVector)))
    .topK(topK)
    .outputFields(List.of("*"))
    .consistencyLevel(EVENTUALLY)
    .build());
// 解析 result.getEntity().get("text") 返回 Document
```

#### MilvusDocumentInitializer.java（47 行）

**职责**：应用启动时，仅在 Milvus collection 为空时加载文档。

```java
@PostConstruct
public void init() {
    if (!milvusRetriever.isEmpty()) {
        log.info("已有数据，跳过加载");
        return;
    }
    // 加载 37 条 .md 文档并写入 Milvus
    List<Document> docs = documentLoader.loadMarkdowns();
    for (Document doc : docs) milvusRetriever.storeDocument(doc);
}
```

---

### 6.8 tools/ 包 — 工具

#### ToolRegistration.java（42 行）

将所有 @Tool 注册成 `ToolCallback[]` Bean。

```java
@Bean
public ToolCallback[] allTools() {
    return ToolCallbacks.from(
        new KnowledgeSearchTool(),  // 知识库搜索
        new WebSearchTool(key),     // 网页搜索
        // ... 共 9 个工具
    );
}
```

#### KnowledgeSearchTool.java（38 行 → 47 行，升级后）

**职责**：Agent 调用的知识库搜索入口。

```java
@Tool(description = "Search the romantic relationship knowledge base...")
public String searchKnowledge(String query, Integer topK) {
    if (ragProps.getType() == RetrieverType.hybrid && milvusRetriever != null) {
        results = milvusRetriever.search(query, k);           // 走 Milvus
    } else {
        results = PgVectorVectorStore.similaritySearch(...);   // 走 PGvector
    }
    // 格式化返回
    return results.stream().map(doc -> "• " + doc.getText()).collect(Collectors.joining("\n\n"));
}
```

**升级变化**：新增 `HybridRetrievalProperties` 和 `MilvusVectorRetriever` 的 `@Autowired(required=false)` 注入，根据 `rag.type` 切换检索后端。

#### 其他工具简述

| 类 | 工具名 | 功能 |
|-----|--------|------|
| WebSearchTool | searchWeb | 调用搜索 API |
| WebScrapingTool | scrapeWebPage | 抓取 URL 内容 |
| BaiduImageSearchTool | searchBaiduImages | 百度图片搜索 |
| FileOperationTool | writeFile / readFile | 读写文件 |
| TerminalOperationTool | executeTerminalCommand | 执行系统命令 |
| PDFGenerationTool | generatePDF | iText 生成 PDF |
| ResourceDownloadTool | downloadResource | 下载网络资源 |
| TerminateTool | doTerminate | Agent 终止 |

---

### 6.9 advisor/MyLoggerAdvisor.java（64 行）

**职责**：ChatClient 的请求/响应日志拦截器。

```java
void logRequest(ChatClientRequest request) { log.info("AI Request: {}", request.prompt()); }
void logResponse(ChatClientResponse response) { log.info("response: {}", ...); }
```

---

### 6.10 harness/observability/ 包 — 可观测性（新增）

#### ObservabilityConfig.java

```java
@Configuration
public class ObservabilityConfig {
    @Value("${management.otlp.tracing.endpoint:}")
    private String otlpEndpoint;

    @PostConstruct
    public void init() {
        if (otlpEndpoint.isBlank()) {
            log.warn("OTLP 未配置，追踪不会导出");
        } else {
            log.info("OpenTelemetry tracing → {}", otlpEndpoint);
        }
    }
}
```

**工作原理**：Spring Boot Actuator + Micrometer Tracing 自动检测到 `opentelemetry-exporter-otlp` 在 classpath 上，自动创建 OTLP 导出器。无需手动配置 Bean。

**自动生成 span 的操作**：

| 操作 | Span 名 |
|------|---------|
| LLM 调用 | `ChatModel.generate` |
| 工具调用 | `ToolCallback.call` |
| 向量检索 | `VectorStore.similaritySearch` |

**配置方式**（3 行环境变量）：

```yaml
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://<langfuse-host>:3000/api/public/otel/v1/traces
```

---

### 6.11 前端（Vue 3）

```
front/
├── src/
│   ├── api/index.js            # API 函数（SSE 创建）
│   ├── views/
│   │   ├── LoveChat.vue        # 简单对话 + RAG 切换
│   │   ├── ManusChat.vue       # Agent 对话
│   │   └── Home.vue            # 首页
│   └── router/index.js         # 路由
```

**api/index.js 升级变化**：

新增 `createLoveChatRagSSE()` → `/Love_app/chat/sse/rag`

**LoveChat.vue 升级变化**：

输入框旁新增 📖 按钮，点亮后走 RAG 流式接口。

---

## 七、配置说明

### 7.1 application.yml

```yaml
spring:
  datasource:      # MySQL（对话记忆）
  ai:
    chat.memory.repository.jdbc.initialize-schema: always
    mcp.client.stdio.servers-configuration: classpath:mcp-servers.json

rag:               # 检索模式（新增）
  type: pgvector   # pgvector | hybrid
  hybrid:
    milvus:
      host: 172.28.23.117
      port: 19530
      collection-name: love_knowledge
      vector-dim: 1024
    rrf:
      k: 60
      topK: 10

management:        # 可观测性（新增）
  tracing:
    enabled: true
    sampling:
      probability: 1.0
```

### 7.2 application-local.yml

```yaml
spring.ai:
  dashscope.api-key: sk-xxx     # 阿里云百炼
  deepseek.api-key: sk-xxx      # DeepSeek

management.otlp.tracing:
  endpoint: http://172.28.23.117:3000/api/public/otel/v1/traces
  headers:
    Authorization: Basic <base64>
```

---

## 八、可观测性链路

```
Spring AI auto-instrumentation
  ├── ChatModel.generate          ← 自动
  ├── ToolCallback.call           ← 自动
  └── VectorStore.similaritySearch ← 自动
          ↓
Micrometer Observation
          ↓
Micrometer Tracing Bridge (micrometer-tracing-bridge-otel)
          ↓
OpenTelemetry OTLP Exporter
          ↓
任意 OTLP 后端（Langfuse / LangSmith / Grafana Tempo / Jaeger）
```

---

## 九、工具一览

| 工具名 | Java 类 | Agent 能否调用 |
|--------|---------|---------------|
| searchKnowledge | KnowledgeSearchTool | ✅（检索 PGvector 或 Milvus）|
| searchWeb | WebSearchTool | ✅ |
| searchBaiduImages | BaiduImageSearchTool | ✅ |
| scrapeWebPage | WebScrapingTool | ✅ |
| writeFile / readFile | FileOperationTool | ✅ |
| executeTerminalCommand | TerminalOperationTool | ✅ |
| generatePDF | PDFGenerationTool | ✅ |
| downloadResource | ResourceDownloadTool | ✅ |
| doTerminate | TerminateTool | ✅（结束对话）|
| MCP 工具 | MCP Server 插件 | ✅ |

---

## 十、升级状态

### 第〇期 ✅ — 先看现状

| 改动 | 文件 |
|------|------|
| Actuator + Micrometer Tracing → OTLP | pom.xml (actuator, tracing-bridge-otel, otlp-exporter) |
| Langfuse 配置 | application.yml (management.otlp.tracing.*) |
| 可观测性检查 | harness/observability/ObservabilityConfig.java |
| 3 行环境变量配置 | application.yml + application-local.yml |
| 12 条 LoveManus 实测 + 分析报告 | Phase0Report.md, scripts/phase0_analysis/ |
| 数据收集器 | ConversationTracker.java（test）|

### 第一期 🔧 — 基础设施 + 混合检索

| 改动 | 文件 |
|------|------|
| Milvus SDK 依赖 | pom.xml (milvus-sdk-java:2.5.5) |
| Milvus Docker Compose | docker/local/docker-compose.yml |
| 检索类型枚举 | retrieval/RetrieverType.java |
| 配置映射 | retrieval/HybridRetrievalProperties.java |
| 条件配置 | retrieval/HybridRetrievalConfig.java |
| Milvus 客户端 | retrieval/MilvusVectorRetriever.java（init/close/createCollection/storeDocument/search/isEmpty/recreateCollection/deleteCollection）|
| 文档初始化 | retrieval/MilvusDocumentInitializer.java |
| 检索切换 | tools/KnowledgeSearchTool.java（rag.type 判断）|
| 流式 RAG 接口 | app/LoveApp.java (doChatByStreamWithRAG) |
| SSE RAG 端点 | controller/AiController.java (chatSseWithRAG) |
| 前端 RAG API | front/src/api/index.js (createLoveChatRagSSE) |
| 前端 RAG 切换 | front/src/views/LoveChat.vue (📖 按钮) |

### 待完成

- 第二期：多租户隔离（ThreadLocal + JWT）
- 第三期：MCP 工具（地图、天气、日历）
- 第四期：RAGAS 质量评估
- RRF 融合排序（Milvus + ES BM25）

---

## 十一、运行方式

```bash
# 正常启动
mvn spring-boot:run

# 基础设施（WSL2）
docker compose -f ~/milvus/docker-compose.yml up -d     # Milvus
docker compose -f ~/langfuse/docker-compose.yml up -d   # Langfuse

# 测试 Agent
curl "http://localhost:8123/api/Love_app/chat/LoveManus?message=hello&sessionId=t1"

# 流式 RAG（前端不需 Agent）
curl "http://localhost:8123/api/Love_app/chat/sse/rag?prompt=你好&chatId=t1"

# 评估 12 条
mvn test -Dtest=Phase0EvaluationTest
cd scripts/phase0_analysis && python analyze.py --input ../../target/phase0-report.json
```

---

## 十二、常见问题

### Agent 和普通聊天的区别？

普通聊天：一问一答，每次独立。

Agent 聊天：AI 自主决定是否调工具、调什么工具、调几次，有完整的 ReAct 循环。

### PGvector 和 Milvus 的区别？

| | PGvector | Milvus |
|--|---------|--------|
| 类型 | PostgreSQL 插件 | 专用向量数据库 |
| 部署 | 随 PostgreSQL | 独立 Docker 容器 |
| 规模 | 万级 | 百万级以上 |
| 内存 | 少 | 多（索引在内存）|

### 怎么切换检索模式？

改一行配置 `rag.type: hybrid` 后重启。

### 追踪数据会发出去吗？

不。OTLP endpoint 配的是本机 WSL2 的 Langfuse，数据不出本机。
