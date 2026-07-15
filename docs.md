# lwx-ai-agent 项目说明文档（Spring AI 入门版）

> 目标读者：刚入门 Spring AI 的小白。
> 
> 目标：用最细的粒度解释当前项目的每个类、关键方法与整体运行流程。

## 1. 项目定位与整体结构

这个项目是一个“恋爱咨询 AI 应用”的最小可用实现，核心功能包括：

- 基础对话（Chat），支持多轮上下文。
- 结构化输出（报告样式）。
- RAG（检索增强）对话：本地向量库与云端检索两条路径。
- 基础健康检查接口与若干演示类。

**核心技术栈：**

- Spring Boot 3.4.4
- Spring AI 1.1.7（使用 BOM 管理）
- DashScope ChatModel（阿里云百炼）
- JDBC ChatMemory（MySQL）
- PgVector 向量库（PostgreSQL）

**包结构概览：**

```
cn.lwx.lwxaiagent
├─ LwxAiAgentApplication          // Spring Boot 启动入口
├─ advisor                        // ChatClient 的顾问/拦截器
├─ app                            // 业务入口：恋爱咨询 Chat 应用
├─ chatMemory                     // 自定义 ChatMemory 占位实现
├─ controller                     // Web 控制器
├─ demo                           // 演示用类
├─ rag                            // RAG 相关：文档加载、向量库、检索增强
└─ Config                         // 自定义配置属性
```

---

## 2. 配置文件说明

### 2.1 `src/main/resources/application.yml`

- **MySQL 数据源**：用于 ChatMemory 持久化（聊天历史）。
- **pgvector 数据源**：用于向量库（RAG）。
- **初始化 SQL**：`spring.ai.chat.memory.repository.jdbc.initialize-schema: always` 让框架自动建表。

关键字段说明：

- `spring.datasource.*`：**ChatMemory 使用的数据库**（这里是 MySQL）。
- `app.pgvector.datasource.*`：**向量库使用的数据库**（这里是 PostgreSQL）。
- `spring.ai.chat.memory.repository.jdbc.schema`：指定建表 SQL（MySQL 版本）。

> 注意：ChatMemory 只读取 `spring.datasource`，不会读取 `app.pgvector.datasource`。

### 2.2 `src/main/resources/application-local.yml`

- 用于本地环境的 DashScope API 配置。
- `spring.ai.dashscope.chat.options.model` 指定模型名称。

> 建议：API Key 应使用环境变量或配置中心管理，避免直接写入版本库。

---

## 3. Maven 依赖说明（`pom.xml`）

关键依赖用途：

- `spring-boot-starter-web`：提供 REST API 能力。
- `spring-ai-alibaba-starter-dashscope`：对接百炼 ChatModel。
- `spring-ai-starter-model-chat-memory-repository-jdbc`：ChatMemory JDBC 实现。
- `spring-ai-starter-vector-store-pgvector`：PgVector 向量库支持。
- `spring-ai-advisors-vector-store`：提供向量检索顾问。
- `spring-ai-markdown-document-reader`：读取 Markdown 文档用于 RAG。

---

## 4. 运行流程（从启动到对话）

### 4.1 启动阶段

1. `LwxAiAgentApplication.main()` 启动 Spring Boot。
2. 读取 `application.yml` 与 `application-local.yml`。
3. 自动创建 `ChatModel`、`JdbcChatMemoryRepository`、`VectorStore` 等 Bean。
4. RAG 文档加载（`LoveAppDocumentLoader`）在向量库初始化时触发。

### 4.2 对话流程（以 `LoveApp.doChat` 为例）

1. `LoveApp` 通过 `ChatClient` 构造 Prompt。
2. `MessageChatMemoryAdvisor` 根据 `chatId` 读写历史记录。
3. `MyLoggerAdvisor` 打印请求/响应日志。
4. `ChatModel` 调用大模型并返回响应。

---

## 5. 类与方法详解

### 5.1 `LwxAiAgentApplication`
**路径：** `src/main/java/cn/lwx/lwxaiagent/LwxAiAgentApplication.java`

**作用：** Spring Boot 启动入口。

- `main(String[] args)`
  - 启动 Spring 容器。
  - 触发自动配置与 Bean 扫描。

**边界：**
- 不做任何业务逻辑。

---

### 5.2 `LoveApp`（核心业务入口）
**路径：** `src/main/java/cn/lwx/lwxaiagent/app/LoveApp.java`

#### 类级职责
- 封装“恋爱咨询”的 AI 对话能力。
- 管理 ChatClient 的系统提示词、记忆、RAG。

#### 字段说明
- `chatClient`：统一的对话入口。
- `SYSTEM_PROMPT`：固定的系统提示词（角色与问题边界）。
- `loveAppRagCloudAdvisor`：云端检索顾问（百炼）。
- `PgVectorVectorStore`：本地 PgVector 向量库。
- `LoveAppVectorStore`：内存向量库（SimpleVectorStore）。

#### 构造方法
- `LoveApp(ChatModel dashscopeChatModel, JdbcChatMemoryRepository chatMemoryRepository)`
  - 创建 `ChatMemory`：
    - `MessageWindowChatMemory` + JDBC（真正使用）。
    - 也创建了内存版本（目前未使用）。
  - 创建 `ChatClient`：
    - 设置默认系统提示词。
    - 绑定 `MessageChatMemoryAdvisor`。
    - 绑定 `MyLoggerAdvisor`。

#### 方法详解

- `String doChat(String message, String chatId)`
  - **用途：** 基础对话。
  - **输入：**
    - `message` 用户文本。
    - `chatId` 对话分组 ID。
  - **过程：**
    - `.prompt().user(message)` 构建 Prompt。
    - `.advisors(spec -> spec.param(CONVERSATION_ID, chatId))` 绑定对话记忆。
  - **输出：**模型返回的纯文本。

- `LoveReport doChatWithReport(String message, String chatId)`
  - **用途：**输出“恋爱报告”。
  - **核心点：**
    - 使用 `system()` 覆盖系统提示词并追加结构化输出要求。
    - `.entity(LoveReport.class)` 做结构化反序列化。
  - **边界：**模型如果输出格式不符合 JSON Schema 会报错或解析失败。

- `String doChatWithRAG(String message, String chatId)`
  - **用途：**RAG 检索增强对话。
  - **流程：**
    - 构造 `QuestionAnswerAdvisor`（本地 PgVector）。
    - 可切换多种 RAG 策略（本地/云端/自定义过滤）。
    - 当前启用：`LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(LoveAppVectorStore, "单身")`。
  - **边界：**如果向量库未初始化或为空，检索返回会为空。

#### 内部记录类
- `record LoveReport(String title, List<String> suggestions)`
  - 用于结构化输出。

---

### 5.3 `MyLoggerAdvisor`
**路径：** `src/main/java/cn/lwx/lwxaiagent/advisor/MyLoggerAdvisor.java`

**职责：**打印 AI 请求和响应，方便调试。

- `adviseCall(...)`
  - 同步调用时：记录请求和最终响应。

- `adviseStream(...)`
  - 流式调用时：聚合碎片，再打印最终响应。

- `logRequest(...)` / `logResponse(...)`
  - 打印 Prompt 与输出文本。

**边界：**
- 仅日志，不修改请求/响应。

---

### 5.4 `ReReadingAdvisor`
**路径：** `src/main/java/cn/lwx/lwxaiagent/advisor/ReReadingAdvisor.java`

**职责：**将用户问题重复一次，以增强模型理解稳定性。

- `before(...)`
  - 用 `PromptTemplate` 把原问题复制一次。
  - **边界：**不改写语义，只做重复提示。

- `after(...)`
  - 不处理响应。

- `withOrder(int order)`
  - 可设置执行顺序。

---

### 5.5 `LoveAppDocumentLoader`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/LoveAppDocumentLoader.java`

**职责：**加载 `classpath:document/*.md` 下的 Markdown 文档。

- `loadMarkdowns()`
  - 使用 `MarkdownDocumentReader` 解析。
  - 增加 metadata：`filename`、`status`。
  - `status` 从文件名末尾截取（例如“单身/恋爱/已婚”）。

**边界：**
- 读取失败会打印日志并返回空集合。

---

### 5.6 `LoveAppVectorStoreConfig`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/LoveAppVectorStoreConfig.java`

**职责：**构建本地内存向量库（SimpleVectorStore）。

- `VectorStore LoveAppVectorStore(EmbeddingModel embeddingModel)`
  - 加载 Markdown 文档。
  - 可选择分词（当前注释掉）。
  - 使用 `MyKeywordEnricher` 生成关键词元数据。
  - `vectorStore.add(...)` 将文档入库。

**边界：**
- 进程重启后向量库会丢失。

---

### 5.7 `PgVectorVectorStoreConfig`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/PgVectorVectorStoreConfig.java`

**职责：**构建 PgVector 向量库，并初始化数据。

- `VectorStore PgVectorVectorStore(EmbeddingModel embeddingModel)`
  - 使用 `PgvectorProperties` 创建独立 PostgreSQL 数据源。
  - `PgVectorStore.builder(...)` 配置索引与表结构。
  - 如果 `vector_store` 表记录数为 0：
    - 加载文档。
    - 去重。
    - 分批写入（DashScope 10 条限制）。

**边界：**
- 未配置 pgvector 数据源会导致 Bean 创建失败。
- 依赖 `vector_store` 表存在（初始化设置为 true 会自动建表）。

---

### 5.8 `LoveAppRagCustomAdvisorFactory`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/LoveAppRagCustomAdvisorFactory.java`

**职责：**创建带过滤条件的 RAG Advisor。

- `createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status)`
  - 使用 FilterExpressionBuilder 限定 `status` 字段。
  - 设置相似度阈值与 TopK。
  - 返回 `RetrievalAugmentationAdvisor`。

**边界：**
- 过滤字段必须存在于文档 metadata 中。

---

### 5.9 `LoveAppRagCloudAdvisorConfig`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/LoveAppRagCloudAdvisorConfig.java`

**职责：**配置百炼（DashScope）检索增强。

- `DashScopeApi dashScopepi()`
  - 根据 API Key 创建客户端。

- `Advisor loveAppRagCloudAdvisor(DashScopeApi dashScopeApi)`
  - 绑定知识库名称 `恋爱大师`。
  - 创建云端检索器。
  - 返回 RAG Advisor。

**边界：**
- 依赖 DashScope 远程服务与知识库已存在。

---

### 5.10 `QueryRewriter`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/QueryRewriter.java`

**职责：**用模型改写用户问题，提高检索效果。

- `doRewrite(String prompt)`
  - 将文本包装成 `Query`。
  - 使用 `RewriteQueryTransformer` 生成改写版本。

**边界：**
- 需消耗模型 Token，可能增加成本。

---

### 5.11 `MyKeywordEnricher`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/MyKeywordEnricher.java`

**职责：**为文档生成关键词元数据。

- `enrichDocuments(List<Document> documents)`
  - 使用 `KeywordMetadataEnricher`。
  - 默认 5 个关键词。

---

### 5.12 `MyTokenTextSplitter`
**路径：** `src/main/java/cn/lwx/lwxaiagent/rag/MyTokenTextSplitter.java`

**职责：**对文档做分段切分，减少向量长度。

- `splitDocuments(...)`
  - 默认切分器。

- `splitCustomized(...)`
  - 自定义 TokenTextSplitter 参数。
  - 适合控制最大长度与保留标点。

---

### 5.13 `FileChatMemory`
**路径：** `src/main/java/cn/lwx/lwxaiagent/chatMemory/FileChatMemory.java`

**职责：**ChatMemory 的占位实现。

- `add(...)`：调用接口默认实现。
- `add(List<Message>)`：TODO。
- `get(...)`：暂返回空列表。
- `clear(...)`：TODO。

**边界：**
- 目前不可用，仅用于练习。

---

### 5.14 `healthController`
**路径：** `src/main/java/cn/lwx/lwxaiagent/controller/healthController.java`

**职责：**健康检查接口。

- `GET /health`
  - 返回 `ok`。
  - 仅用于存活探测。

---

### 5.15 `MyMultiQueryExpanderDemo`
**路径：** `src/main/java/cn/lwx/lwxaiagent/demo/MyMultiQueryExpanderDemo.java`

**职责：**演示多查询扩展。

- `expand(String query)`
  - 使用 `MultiQueryExpander` 生成多个查询变体。
  - 当前写死了示例问题（可优化成参数）。

---

### 5.16 `SpringAiAiInvoke`
**路径：** `src/main/java/cn/lwx/lwxaiagent/demo/invoke/SpringAiAiInvoke.java`

**职责：**启动后立即调用一次模型，作为可用性验证。

- `run(...)`
  - 调用模型返回结果。
  - 打印输出文本。

---

## 6. 常见问题与小白提示

### 6.1 为什么数据库里没有对话记录？
- 必须给同一个 `chatId` 才能读到历史。
- `JdbcChatMemoryRepository` 会在 `chatId` 下覆盖或更新窗口。
- 如果你每次都换 chatId，历史就分散了。

### 6.2 RAG 没生效怎么办？
- 确认 `LoveAppDocumentLoader` 是否成功读取 Markdown。
- 确认向量库是否成功入库。
- 确认检索 Advisor 是否真正启用（当前部分是注释状态）。

### 6.3 PgVector 与 ChatMemory 如何共存？
- ChatMemory 走 `spring.datasource`。
- PgVector 走 `app.pgvector.datasource`。
- 两个库互不影响。

---

## 7. 下一步你可以做的练习

1. 为 `FileChatMemory` 写入/读取文件实现。
2. 在 `LoveApp` 中切换不同 RAG Advisor，观察效果差异。
3. 把 `MyMultiQueryExpanderDemo` 变成可复用的服务。
4. 将 Markdown 文档分词后再入库，观察检索表现。

---

如需我再补充“测试类说明”或“接口调用示例”，告诉我即可。

