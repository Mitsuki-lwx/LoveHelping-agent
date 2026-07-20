# lwx-ai-agent 升级全解

> 写给刚接手项目的你：本文档只讲**升级后**新增和改动的代码，不讲原有逻辑。
> 从第〇期（可观测性）到第一期（混合检索），每个文件、每个方法是干什么的、为什么这么写。

---

## 目录

1. [升级概览](#一升级概览)
2. [第〇期：可观测性](#二第〇期可观测性)
3. [第一期：混合检索框架](#三第一期混合检索框架)
4. [第一期：Milvus 集成](#四第一期milvus-集成)
5. [第一期：ES BM25 + RRF（已写好代码，等待 ES 就绪）](#五第一期es-bm25--rrf已写好代码等待-es-就绪)
6. [第一期：控制器和前端](#六第一期控制器和前端)
7. [配置说明](#七配置说明)

---

## 一、升级概览

### 升级前有什么？

```
用户 → AiController → LoveApp → ChatClient → LLM（回答）
                                          → PGvector（知识库搜索）
                                          → @Tool（Agent 工具）
```

可观测性：0。出问题靠猜。

### 升级后多了什么？

```
用户 → AiController → LoveApp → ChatClient → LLM
                     → LoveManus（Agent） → ToolCallAgent → KnowledgeSearchTool
                                                              ├→ PGvector（默认）
                                                              └→ Milvus + ES → RRF（hybrid 模式）
                     → /sse/rag（流式 RAG，不加 Agent）

可观测性：Actuator → Micrometer → OTLP → Langfuse（全链路追踪）
```

---

## 二、第〇期：可观测性

### 2.1 整体链路

看不懂配置没关系，记住这条链路就行：

```
Spring AI 自动记录
  ├── 每次 LLM 调用 → "ChatModel.generate" span
  ├── 每次 @Tool 调用 → "ToolCallback.call" span
  └── 每次向量检索 → "VectorStore.similaritySearch" span
        ↓
Micrometer（收集）
        ↓
OTLP 协议（传输）
        ↓
任意 OTLP 后端（Langfuse / LangSmith / Jaeger）
```

不需要手动写任何埋点代码，Spring AI + Actuator 自动完成。

### 2.2 配置方式

3 行配置搞定（在 `application-local.yml` 里）：

```yaml
management:
  tracing:
    enabled: true          # 开启追踪
    sampling:
      probability: 1.0     # 采样率 100%（所有请求都追踪）
  otlp:
    tracing:
      endpoint: http://172.28.23.117:3000/api/public/otel/v1/traces  # 发给 Langfuse
```

### 2.3 涉及的代码

**`harness/observability/ObservabilityConfig.java`**（新增，24 行）

```java
@Configuration
public class ObservabilityConfig {

    // 从 application.yml 读取 OTLP 端点地址
    @Value("${management.otlp.tracing.endpoint:}")
    private String otlpEndpoint;

    @PostConstruct
    public void init() {
        if (otlpEndpoint.isBlank()) {
            log.warn("OTLP 没配，不导出追踪数据");
        } else {
            log.info("OTLP 已启用 → 导出到 {}", otlpEndpoint);
        }
    }
}
```

就这么多。这个类只做一件事：**启动时检查 OTLP 有没有配好，打印日志告诉你**。真正干活的是 Spring Boot 自带的 Actuator 和 Micrometer Tracing。

### 2.4 pom.xml 加了什么

```xml
<!-- 这三个依赖让 Spring AI 的自动追踪变成 OTLP 导出 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

---

## 三、第一期：混合检索框架

### 3.1 架构图

```
KnowledgeSearchTool（Agent 调用的入口）
       │
       ├── rag.type=pgvector → PgVectorVectorStore.similaritySearch()
       │
       └── rag.type=hybrid → HybridRetrievalService.search()
                                ├── MilvusVectorRetriever.search()   （向量语义）
                                └── ESKeywordRetriever.search()      （BM25 关键词）
                                      ↓
                                 RRFFuser.fuse()                     （融合排序）
                                      ↓
                                 Top K 结果返回
```

### 3.2 文件列表

| 文件 | 包 | 角色 |
|------|------|------|
| `RetrieverType.java` | retrieval | 检索类型枚举 |
| `HybridRetrievalProperties.java` | retrieval | 配置映射 |
| `MilvusVectorRetriever.java` | retrieval | Milvus 客户端 |
| `MilvusDocumentInitializer.java` | retrieval | 文档初始化 |
| `ScoredDocument.java` | retrieval | 排名结果包装 |
| `ESKeywordRetriever.java` | retrieval | ES 客户端（已写好待用）|
| `RRFFuser.java` | retrieval | RRF 融合排序 |
| `HybridRetrievalService.java` | retrieval | 混合检索入口 |
| `HybridRetrievalConfig.java` | config | Spring Bean 配置 |
| `KnowledgeSearchTool.java`（修改）| tools | 检索路由（pgvector/hybrid）|

---

## 四、第一期：Milvus 集成

### 4.1 MilvusVectorRetriever.java —— 核心类

**位置**：`retrieval/MilvusVectorRetriever.java`

**它是干什么的**：Milvus 的 Java 客户端。封装了"连接、建表、写入、搜索"四个操作。

#### 构造方法

```java
public MilvusVectorRetriever(
    String host,           // Milvus 地址（默认 localhost）
    int port,              // Milvus 端口（默认 19530）
    String collectionName, // 集合名（默认 love_knowledge）
    int vectorDim,         // 向量维度（默认 1024，需和 embedding 模型一致）
    EmbeddingModel model   // 嵌入模型，用于将文本转成向量
)
```

#### 方法概览

| 方法 | 用途 | 何时调用 |
|------|------|---------|
| `init()` | 连接 Milvus + 确保集合存在 | 应用启动时（@PostConstruct）|
| `close()` | 断开连接 | 应用关闭时（@PreDestroy）|
| `storeDocument(Document)` | 写入一条文档到 Milvus | 文档初始化时 |
| `search(String, int)` | 搜索最相似的 topK 条 | Agent 调用 KnowledgeSearchTool 时 |
| `isEmpty()` | 判断集合是否为空 | 启动时决定要不要导入文档 |
| `recreateCollection()` | 删除重建集合 | 集合状态异常时恢复 |
| `deleteCollection()` | 删除整个集合 | 清理时 |

#### 为什么 storeDocument 要包装成 JsonObject？

因为 Milvus v2 SDK 的 `InsertReq.data()` 只接受 `List<JsonObject>`，不能像 PGvector 那样直接传 Document。所以必须手动把数据组装成：

```json
{
  "vector": [0.1, 0.2, 0.3, ...],  // float[] → JSON 数组
  "text": "文档内容...",             // 原文
  "metadata": "{\"key\":\"val\"}"   // 元数据 JSON 字符串
}
```

#### search() 的返回数据

```java
Document(
    text = "文档原文内容",
    metadata = {
        "milvus_id": "467742480239380117",  // Milvus 内部 ID
        "score": "0.6280757"                 // 余弦相似度分数
    }
)
```

这些 metadata 后续会传给 `HybridRetrievalService` 解析成 `ScoredDocument`，供 RRF 融合使用。

### 4.2 MilvusDocumentInitializer.java —— 文档导入器

**位置**：`retrieval/MilvusDocumentInitializer.java`

**作用**：启动时检查 Milvus 是否为空，空则从 .md 文件加载文档。

**设计逻辑**：

```
启动 → isEmpty() = true → 重建集合 → 加载 37 条文档 → 结束
启动 → isEmpty() = false → 跳过（已有数据，不重复导入）
```

`@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")` 表示只有 `rag.type=hybrid` 时这个类才会生效。

### 4.3 为什么 Milvus 能搜到了，之前一直搜不到？

踩了三个坑：

**坑 1：JSON 解析错误**（第 1 次修复）
- 问题：用了 `enableDynamicField(true)`，Milvus 把 text 字段当 JSON 解析，但文本里有换行和引号
- 修复：改为显式 schema（定死 text 是 VarChar，metadata 是 VarChar）

**坑 2：collection not loaded**（第 2 次修复）
- 问题：创建 collection 后忘记 `loadCollection`，搜索时找不到数据
- 修复：在 `createCollection()` 末尾加 `client.loadCollection()`

**坑 3：index not found**（第 3 次修复）
- 问题：旧的 collection 没有索引，`loadCollection` 失败
- 修复：`ensureCollection()` 里 catch 加载异常，自动重建

---

## 五、第一期：ES BM25 + RRF（已写好代码，等待 ES 就绪）

### 5.1 ESKeywordRetriever.java —— ES 客户端

**位置**：`retrieval/ESKeywordRetriever.java`

**方法概览**：

| 方法 | 说明 |
|------|------|
| `init()` | 连接 ES，创建 REST 客户端 |
| `close()` | 关闭连接 |
| `indexExists()` | 检查索引是否存在 |
| `createIndexWithIk()` | 创建带 IK 中文分词的索引 |
| `indexDocument(Document)` | 将文档写入 ES |
| `search(String, int)` | BM25 关键词搜索 |

**搜索策略**（`search()` 方法）：

```java
query.bool.should([
  multi_match(fields: ["text^2", "title"], query: "失恋怎么办"),  // 语义匹配
  match(field: "text.ik", query: "失恋怎么办")                       // IK 分词精确匹配
])
```

用 `bool.should` 表示"任一匹配即可"，两个策略互为补充。

**返回数据**：

```java
ScoredDocument(
    id = "ES内部ID",
    document = Document(text = "...", metadata = {"title": "...", "es_id": "..."}),
    score = 12.34  // BM25 分数（无上限）
)
```

### 5.2 RRFFuser.java —— 融合排序

**位置**：`retrieval/RRFFuser.java`

**公式**：

```
rrf_score(d) = 1/(k + rank_milvus(d)) + 1/(k + rank_es(d))
```

**举例**：

假设 k=60，文档 A 在 Milvus 排第 2、在 ES 排第 5：
```
rrf_score = 1/(60+2) + 1/(60+5) = 0.0161 + 0.0154 = 0.0315
```

**为什么不用加权平均（weighted average）？**
- Milvus 返回余弦相似度（0~1），ES 返回 BM25 分数（无上限）
- 两个分数尺度不同，不能直接加权
- RRF 只依赖排名，不依赖分数绝对值，天然解决尺度问题

### 5.3 HybridRetrievalService.java —— 统一入口

**位置**：`retrieval/HybridRetrievalService.java`

**调用链路**：

```
KnowledgeSearchTool
  → HybridRetrievalService.search("失恋后怎么办", topK=5)
    → MilvusVectorRetriever.search(..., channelN=10)      ← 取 2 倍供 RRF 候选
    → ESKeywordRetriever.search(..., channelN=10)
    → RRFFuser.fuse(milvusResults, esResults)
    → 返回 topK=5 条最终结果
```

**为什么每个通道多取一倍？**
因为 RRF 输入越多，融合效果越好。用户要 5 条，各取 10 条候选，RRF 从中挑最靠谱的 5 条。

### 5.4 KnowledgeSearchTool.java —— 检索路由（修改）

升级前：

```java
// 固定走 PGvector
results = PgVectorVectorStore.similaritySearch(...);
```

升级后：

```java
if (hybrid 模式 && HybridRetrievalService 可用) {
    results = hybridRetrievalService.search(query, k);  // Milvus + ES → RRF
} else if (hybrid 模式 && Milvus 可用) {
    results = milvusRetriever.search(query, k);          // 仅 Milvus（ES 没准备好时降级）
} else {
    results = PgVectorVectorStore.similaritySearch(...); // 默认 PGvector
}
```

`@Autowired(required = false)` 表示"有这个 Bean 就用，没有也不报错"。因为 `HybridRetrievalService` 和 `MilvusVectorRetriever` 只在 `rag.type=hybrid` 时才会被 Spring 创建。

---

## 六、第一期：控制器和前端

### 6.1 新端点：/Love_app/chat/sse/rag

**后端**（`AiController.java`）：

```java
@GetMapping(value = "Love_app/chat/sse/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chatSseWithRAG(String prompt, String chatId) {
    return loveApp.doChatByStreamWithRAG(prompt, chatId);
}
```

**后端**（`LoveApp.java`）：

```java
public Flux<String> doChatByStreamWithRAG(String message, String chatId) {
    QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(PgVectorVectorStore).build();
    return chatClient
            .prompt().user(message)
            .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
            .advisors(ragAdvisor)     // ← 添加 RAG 拦截器，自动检索 PGvector
            .stream().content();
}
```

`QuestionAnswerAdvisor` 是 Spring AI 内置的拦截器，它会：
1. 把用户问题转成向量
2. 在 PGvector 里搜索最相似的文档
3. 把搜到的文档拼到系统提示词里
4. 再调用 LLM 生成回答

### 6.2 前端改动

**`front/src/api/index.js`** —— 新增 API 函数：

```javascript
export function createLoveChatRagSSE(prompt, chatId, handlers) {
  const url = `/Love_app/chat/sse/rag?prompt=${encodeURIComponent(prompt)}&chatId=${encodeURIComponent(chatId)}`
  return createSSE(url, handlers)
}
```

**`front/src/views/LoveChat.vue`** —— 新增 RAG 切换按钮：

- 输入框旁多了个 📖 按钮
- 默认关闭（不检索）
- 点亮后，发消息走 `/sse/rag` 端点（带 PGvector 知识库检索）
- 不点亮，走普通 `/sse` 端点（纯聊天）

---

## 七、配置说明

### 7.1 application.yml（新增部分）

```yaml
# 检索模式切换
rag:
  type: pgvector                    # pgvector（默认，无需 Docker）| hybrid（需 Milvus + ES）
  hybrid:
    es:
      uris: http://localhost:9200    # ES 地址
      index: love_knowledge          # ES 索引名
    milvus:
      host: 172.28.23.117           # Milvus 地址（WSL2 IP）
      port: 19530                   # Milvus 端口
      collection-name: love_knowledge
      vector-dim: 1024              # 向量维度（需和 embedding 模型一致）
    rrf:
      k: 60                         # RRF 平滑参数
      topK: 10                      # 最终返回结果数

# 可观测性
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0
```

### 7.2 切换模式

```yaml
# 改一行配置，重启生效
rag:
  type: hybrid    # pgvector → hybrid
```

### 7.3 rag.type 控制哪些 Bean 生效

| rag.type | 生效的 Bean | 不生效的 Bean |
|----------|------------|---------------|
| `pgvector` | PgVectorVectorStore | MilvusVectorRetriever, ESKeywordRetriever, RRFFuser, HybridRetrievalService, MilvusDocumentInitializer |
| `hybrid` | 以上全部 | — |

这是通过 `@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")` 实现的。

---

## 附：常见问题

### Q: 升级后系统多了哪些外部依赖？

| 组件 | 是否必需 | 部署位置 |
|------|---------|---------|
| Langfuse | 否（不配就不导出追踪） | WSL2 Docker |
| Milvus | hybrid 模式必需 | WSL2 Docker |
| ES | hybrid 模式可选（已写好代码待用） | WSL2 Docker 或远程 |

### Q: Milvus 和 PGvector 的区别？

| | PGvector | Milvus |
|--|---------|--------|
| 类型 | PostgreSQL 插件 | 专用向量数据库 |
| 存数据 | Spring AI 自动做 | 手动包装 JsonObject |
| 搜索 | Spring AI 自动做 | 手动发 SearchReq |
| 维度 | 自动匹配 | 建表时需指定 |
| 规模 | 万级 | 百万级 |

### Q: RRF 在什么时候用的？

当 `rag.type=hybrid` 且 `ESKeywordRetriever` 和 `MilvusVectorRetriever` 都可用时，`KnowledgeSearchTool` 走 `HybridRetrievalService`，内部调用 `RRFFuser.fuse()` 做融合排序。

如果 ES 还没就绪，降级为仅走 Milvus。

### Q: 怎么确认 Milvus 搜到了数据？

看应用日志：

```
Milvus search: query='失恋后怎么办' topK=5 vectorDim=1024
Milvus returned 5 results for '失恋后怎么办'
```

如果只有第一行没有第二行，说明搜索出错或没命中。

### Q: 我改维度了怎么办？

改 `application.yml` 里的 `vector-dim`，然后重启。`MilvusDocumentInitializer` 发现 `isEmpty()` 返回 true（新维度下搜索不到旧数据），会自动重建 collection 并重新导入 37 条文档。
