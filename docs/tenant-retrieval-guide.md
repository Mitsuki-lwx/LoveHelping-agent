# tenant + retrieval 包完全解读

> 写给第一次接触这两个包的你。不讲废话，每段代码、每个概念、每条流程都说明白。
>
> 日期: 2026-07-19

---

## 目录

1. [retrieval 包：混合检索](#一retrieval-包混合检索)
   - [1.1 为什么需要混合检索](#11-为什么需要混合检索)
   - [1.2 架构图](#12-架构图)
   - [1.3 每个类的详细说明](#13-每个类的详细说明)
   - [1.4 核心流程：一次完整的检索](#14-核心流程一次完整的检索)
2. [tenant 包：多租户 + 用户管理](#一tenant-包多租户--用户管理)
   - [2.1 为什么需要多租户](#21-为什么需要多租户)
   - [2.2 架构图](#22-架构图)
   - [2.3 每个类的详细说明](#23-每个类的详细说明)
   - [2.4 核心流程：一次带 token 的请求](#24-核心流程一次带-token-的请求)
   - [2.5 核心流程：注册登录](#25-核心流程注册登录)
3. [两个包的交互关系](#三两个包的交互关系)
4. [配置对照表](#四配置对照表)
5. [常见问题](#五常见问题)

---

## 一、retrieval 包：混合检索

包路径：`src/main/java/cn/lwx/lwxaiagent/retrieval/`

### 1.1 为什么需要混合检索

检索（把用户的问题和知识库中最相关的内容匹配上）有两种主流方法：

**向量检索（Vector Search）**
- 把文字转成向量（一串数字），找"意思最接近"的
- 优点：能理解同义词、近义词，比如搜"失恋了"也能命中"分手后如何调整"
- 缺点：对精确关键词不敏感，搜"冷战"可能命中"冷暴力"但匹配度不够精准
- 代表：Milvus、PGvector

**关键词检索（BM25）**
- 像搜索引擎那样，找出包含相同关键词的文档
- 优点：精确命中关键词，"冷战"就是"冷战"，不会匹配到不相关的内容
- 缺点：词义不同就搜不到，"失恋"和"分手"是两个词
- 代表：Elasticsearch

**混合检索 = 向量 + 关键词，然后用 RRF 融合排序**

把两种方法的搜索结果合并，让结果既准（关键词命中）又全（语义相关）。

### 1.2 架构图

```
用户提问 "失恋后怎么办"
          │
          ▼
KnowledgeSearchTool（Agent 调用的入口）
          │
          ├── rag.type=pgvector
          │       └── PgVectorVectorStore.similaritySearch()
          │
          └── rag.type=hybrid
                  └── HybridRetrievalService.search(tenantId)
                          ├── MilvusVectorRetriever.search()     → List<Document>
                          └── ESKeywordRetriever.search()        → List<ScoredDocument>
                                  │
                                  ▼
                          RRFFuser.fuse(milvusResults, esResults)
                                  │
                                  ▼
                          List<Document>（最终 Top K）
```

### 1.3 每个类的详细说明

---

#### RetrieverType.java（枚举）

```java
public enum RetrieverType { pgvector, hybrid }
```

就两个值。`pgvector` 走旧的 PostgreSQL 向量库，`hybrid` 走新的 Milvus + ES 混合检索。通过 `application.yml` 的 `rag.type` 切换。

---

#### HybridRetrievalProperties.java（配置映射）

**作用**：把 `application.yml` 里 `rag.*` 开头的配置映射成 Java 对象，代码里通过 `ragProps.getType()`、`ragProps.getHybrid().getMilvus().getHost()` 等方式读取。

**对应关系**：

```yaml
rag:
  type: hybrid                  →  RetrieverType type = hybrid
  hybrid:                        →  Hybrid hybrid
    es:                          →    Es es
      uris: http://...:9200      →      String uris
      index: love_knowledge      →      String index
    milvus:                      →    Milvus milvus
      host: 172.28.23.117        →      String host
      port: 19530                →      int port
      collection-name: ...       →      String collectionName
      vector-dim: 1024           →      int vectorDim
    rrf:                         →    Rrf rrf
      k: 60                      →      int k
      topK: 10                   →      int topK
```

**内部类结构**：
```
HybridRetrievalProperties
  ├── type: RetrieverType
  └── hybrid: Hybrid
        ├── es: Es       （uris, index）
        ├── milvus: Milvus（host, port, collectionName, vectorDim）
        └── rrf: Rrf     （k, topK）
```

每个内部类对应 YAML 的一层嵌套。必须是 `static`，因为 Spring 通过反射无参构造创建它们。

---

#### MilvusVectorRetriever.java（Milvus 客户端）

**这是整个检索包的核心类**，封装了 Milvus 的全部交互。

**Milvus 是什么？**
Milvus 是一个专门做向量检索的数据库。你存入"向量 → 文本"的映射，搜索时传入一个向量，它返回最相似的 N 条。

**为什么需要手动包装 JsonObject？**
Milvus v2 SDK 的 `InsertReq.data()` 只接受 `List<JsonObject>`，不能直接传 Java 对象。所以必须手动构造：

```json
{"vector": [0.1, 0.2, ...], "text": "文档内容", "metadata": "{\"filename\":\"...\"}", "tenant_id": "default"}
```

**方法说明：**

| 方法 | 何时被调用 | 做了什么 |
|------|-----------|---------|
| `init()` | 应用启动时（@PostConstruct） | 连接 Milvus，调用 `ensureCollection()` |
| `close()` | 应用关闭时（@PreDestroy） | 断开连接 |
| `ensureCollection()` | init() 内部 | 检查 collection 是否存在，不存在就创建，存在就加载 |
| `createCollection()` | collection 不存在时 | 定义 5 个字段（id/vector/text/metadata/tenant_id），创建索引（COSINE 相似度），加载 |
| `storeDocument(Document)` | 文档初始化时 | 把文本转向量，包装成 JsonObject，插入 Milvus |
| `search(query, topK, tenantId)` | Agent 检索时 | 把查询转向量，在 Milvus 中搜索，按 tenantId 过滤，返回 Document 列表 |
| `isEmpty()` | 启动时判断是否需导入 | 用全零向量搜 1 条，有结果说明非空 |
| `recreateCollection()` | 集合状态异常时 | 删掉重建 |
| `getCollectionName()` | 日志用 | 返回 collection 名 |

**search 的完整执行过程：**

```java
public List<Document> search(String query, int topK, String tenantId) {
    // 1. 把用户输入的文本转成向量（embedding）
    var queryVector = embeddingModel.embed(query);

    // 2. 构建搜索请求
    var builder = SearchReq.builder()
            .collectionName("love_knowledge")   // 搜哪个集合
            .data(List.of(new FloatVec(queryVector)))  // 用这个向量去搜
            .topK(topK)                         // 返回 top K 条
            .outputFields(List.of("*"))          // 返回所有字段
            .consistencyLevel(EVENTUALLY);       // 最终一致性（更快）

    // 3. 如果指定了租户，加上过滤条件
    if (tenantId != null) {
        builder.filter("tenant_id == \"" + tenantId + "\"");
    }

    // 4. 执行搜索
    var resp = client.search(builder.build());

    // 5. 解析结果：每个 SearchResult 包含 id、score、entity（字段值）
    for (SearchResult result : resp.getSearchResults().get(0)) {
        var entity = result.getEntity();
        String text = entity.get("text");               // 取文档原文
        String score = String.valueOf(result.getScore());// 相似度分数
        docs.add(new Document(text, Map.of("score", score, "milvus_id", id)));
    }
    return docs;
}
```

---

#### MilvusDocumentInitializer.java（文档导入器）

**作用**：应用启动时，仅在 Milvus collection 为空时，从 .md 文件加载 37 条文档进去。

```java
@PostConstruct
public void init() {
    // 1. 先检查有没有数据
    if (!milvusRetriever.isEmpty()) {
        log.info("已有数据，跳过");
        return;
    }
    // 2. 没有数据 → 加载 37 个 .md 文档，逐条写入 Milvus
    List<Document> docs = documentLoader.loadMarkdowns();
    for (Document doc : docs) {
        milvusRetriever.storeDocument(doc);  // 每个 doc 都含 tenantId=default
    }
}
```

`@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")` 表示只有 `rag.type=hybrid` 时这个类才生效。

---

#### ScoredDocument.java（排名结果包装）

```java
public class ScoredDocument {
    private String id;         // 文档 ID（Milvus 用 milvus_id，ES 用 _id）
    private Document document; // Spring AI 文档对象
    private double score;      // 原始相似度/BM25 分数
}
```

**为什么需要这个类？**

Milvus 返回的 `Document` 里，id 和 score 塞在 metadata 里（`doc.getMetadata().get("milvus_id")`、`doc.getMetadata().get("score")`），取出来麻烦。ES 返回的直接就是 id + score 作为顶层字段。

RRF 融合时，需要统一的 id + score + Document 结构来操作。`ScoredDocument` 就是做这个统一的。

---

#### ESKeywordRetriever.java（ES 客户端）

**ES 是什么？**
Elasticsearch，一个搜索引擎。用 BM25 算法做关键词匹配。它不像向量检索那样看"意思"，而是看"关键词出现了几次、出现在哪些位置"。

**为什么用 IK 分词？**
ES 默认是按空格分词的，对中文不适用。"失恋后怎么办"会被分成一个字一个字。IK 插件会把它分成"失恋、后、怎么办"这样的有意义的词。

**search 的完整执行过程：**

```java
public List<ScoredDocument> search(String query, int topN, String tenantId) {
    // 构建搜索请求
    SearchResponse<Map> resp = client.search(s -> s
        .index("love_knowledge")     // 搜哪个索引
        .query(q -> q.bool(b -> {
            // 策略1（should）：multi_match 同时匹配 title 和 text
            b.should(s1 -> s1.multiMatch(mm -> mm
                .fields("text^2", "title")  // text 权重 2 倍
                .query(query)));

            // 策略2（should）：match 精确匹配 text.ik 子字段
            b.should(s2 -> s2.match(m -> m
                .field("text.ik")
                .query(query)));

            // 过滤（filter）：按租户精确匹配
            if (tenantId != null) {
                b.filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)));
            }
            return b;
        }))
        .size(topN), Map.class);

    // 解析 Hit 列表 → List<ScoredDocument>
    for (Hit<Map> hit : resp.hits().hits()) {
        Map source = hit.source();
        String text = source.get("text");
        double score = hit.score();
        results.add(new ScoredDocument(hit.id(), new Document(text, ...), score));
    }
}
```

**bool 查询的三个部分：**

```
bool
  ├── should: multi_match  → 语义匹配（title 和 text，权重 text^2）
  ├── should: match        → IK 分词精确匹配（text.ik 子字段）
  └── filter: term         → 按 tenant_id 精确过滤（不计入评分）
```

`should` 表示"任一匹配即可"，相当于 OR。`filter` 是过滤条件，不影响评分。

---

#### RRFFuser.java（RRF 融合排序）

**什么是 RRF（Reciprocal Rank Fusion）？**

把多个排序结果合并成一个。不依赖分数绝对值（因为 Milvus 的余弦相似度是 0~1，ES 的 BM25 可以到几十），只依赖排名。

**公式：**

```
rrf_score(d) = 1/(k + rank_milvus(d)) + 1/(k + rank_es(d))
```

**举例：**

文档 A 在 Milvus 排第 2，在 ES 排第 5，k=60：

```
rrf_score(A) = 1/(60+2) + 1/(60+5) = 0.0161 + 0.0154 = 0.0315
```

文档 B 在 Milvus 排第 1，在 ES 没出现：

```
rrf_score(B) = 1/(60+1) + 0 = 0.0164
```

**k 值的影响：**
- k 越小，排名靠前的文档提升越明显
- k 越大，排名靠后的文档也有机会
- 经验值：Top 50 候选时 k=60，Top 20 时 k=30

**为什么不用加权平均？**
因为两个分数尺度不同，不能直接相加。RRF 只看排名，天然解决尺度问题。

**执行过程：**

```java
public List<Document> fuse(List<ScoredDocument> milvus, List<ScoredDocument> es) {
    // 1. 计算 Milvus 中每条文档的 RRF 贡献
    for (int i = 0; i < milvus.size(); i++) {
        String id = milvus.get(i).getId();
        rrfScores.merge(id, 1.0 / (k + i), Double::sum);
        docMap.putIfAbsent(id, milvus.get(i).getDocument());
    }

    // 2. 加上 ES 的 RRF 贡献
    for (int i = 0; i < es.size(); i++) {
        String id = es.get(i).getId();
        rrfScores.merge(id, 1.0 / (k + i), Double::sum);
        docMap.putIfAbsent(id, es.get(i).getDocument());
    }

    // 3. 按 rrf_score 降序排列，取 topK
    return rrfScores.entrySet().stream()
        .sorted(descending)
        .limit(topK)
        .map(e -> {
            Document doc = docMap.get(e.getKey());
            doc.getMetadata().put("rrf_score", e.getValue());
            return doc;
        });
}
```

---

#### HybridRetrievalService.java（混合检索入口）

**作用**：编排 Milvus + ES → RRF 的完整流程，向上层（KnowledgeSearchTool）屏蔽细节。

```java
public List<Document> search(String query, int userTopK, String tenantId) {
    int channelN = userTopK * 2;  // 每个通道多取一倍供 RRF 候选

    // 1. 搜 Milvus（取 tenantId * 2 条）
    List<Document> milvusDocs = milvusRetriever.search(query, channelN, tenantId);
    // 转成 ScoredDocument（把 metadata 里的 score 解析出来）
    List<ScoredDocument> milvusResults = convert(milvusDocs);

    // 2. 搜 ES（取 tenantId * 2 条）
    List<ScoredDocument> esResults = esRetriever.search(query, channelN, tenantId);

    // 3. RRF 融合 → 返回 userTopK 条
    return rrfFuser.fuse(milvusResults, esResults);
}
```

---

#### KnowledgeSearchTool.java（Agent 调用的入口）

这不是 retrieval 包里的类，但在 `tools/` 包中，是 Agent 调用的入口。

**三种检索模式的选择逻辑：**

```java
@Tool(description = "Search the romantic relationship knowledge base...")
public String searchKnowledge(String query, Integer topK) {
    int k = (topK != null) ? Math.min(topK, 10) : 3;
    String tenantId = TenantContext.getTenantId();  // 可能为 null

    List<Document> results;
    if (ragProps.getType() == hybrid && hybridService != null) {
        results = hybridService.search(query, k, tenantId);          // 模式1: RRF
    } else if (ragProps.getType() == hybrid && milvusRetriever != null) {
        results = milvusRetriever.search(query, k, tenantId);        // 模式2: 仅Milvus
    } else {
        results = PgVectorVectorStore.similaritySearch(              // 模式3: PGvector
            SearchRequest.builder().query(query).topK(k)
                .filterExpression(tenantFilter).build());
    }
    return results.stream().map(doc -> "• " + doc.getText()).collect(joining("\n\n"));
}
```

### 1.4 核心流程：一次完整的检索

以用户说"失恋后怎么办"为例：

```
① AiController 收到请求 → LoveApp/LoveManus 处理
② Agent 思考后决定调 searchKnowledge 工具
③ KnowledgeSearchTool.searchKnowledge()
    读取 ragProps.getType() → 是 hybrid
    读取 TenantContext.getTenantId() → 可能是 null/"default"/"tenant_01"
④ HybridRetrievalService.search("失恋后怎么办", 3, "default")
    ├── MilvusVectorRetriever.search("失恋后怎么办", 6, "default")
    │      → 嵌入查询向量
    │      → 在 Milvus 中搜索（过滤 tenant_id="default"）
    │      → 返回 6 条 Document（含 text + score）
    └── ESKeywordRetriever.search("失恋后怎么办", 6, "default")
           → multi_match + match（IK 分词）
           → filter term tenant_id="default"
           → 返回 6 条 ScoredDocument（含 text + BM25 score）
    ↓
⑤ RRFFuser.fuse(milvusResults(6条), esResults(6条), k=60)
      → 计算 RRF 分数
      → 按分数降序排列
      → 取 topK=3 条
      → 返回 3 条 Document
⑥ 结果格式化（"• 正视自己的焦虑情绪...\n\n• 把注意力放在可控的事上..."）
⑦ Agent 组织语言 → 返回给用户
```

---

## 二、tenant 包：多租户 + 用户管理

包路径：`src/main/java/cn/lwx/lwxaiagent/tenant/`

### 2.1 为什么需要多租户

**多租户 = 一套系统服务多个互不干扰的客户。**

比如：
- 租户 A（"恋爱咨询公司"）有 10 个用户
- 租户 B（"婚恋平台"）有 200 个用户
- 两个租户的数据互相隔离，A 搜不到 B 的文档

**现在的设计是一租户多用户，但预留了多租户扩展。**

- 所有文档默认 `tenantId=default`（单租户模式）
- 新注册用户可指定 `tenantId`（多租户模式）
- 检索时自动按 `tenantId` 过滤
- 不带 token 的请求（老用户）兼容运行，不过滤

### 2.2 架构图

```
请求 → TenantFilter（Filter，最外层，确保 finally 清理 ThreadLocal）
     → SecurityConfig（放行所有，关闭 CSRF）
     → TenantInterceptor（Interceptor，拦截 /Love_app/**）
            │
            ├── 无 Authorization 头 → 放行（不注入租户信息）
            ├── 有 Bearer token    → JwtTokenProvider.parseToken()
            │                           ↓
            │                      注入 TenantContext（ThreadLocal）
            │
            ▼
        业务逻辑（AiController → LoveApp → 各种服务）
            │
            ▼
        TenantFilter.cleanup() finally { TenantContext.clear(); }
```

**Token / 认证流程：**

```
注册 → AuthController → UserService → UserMapper → MySQL → JWT
登录 → AuthController → UserService → UserMapper → 验证密码 → JWT
请求 → Authorization: Bearer <JWT> → TenantInterceptor → JwtTokenProvider.parseToken()
      → 注入 TenantContext → 业务代码用 TenantContext.getTenantId()
```

### 2.3 每个类的详细说明

---

#### TenantContext.java（ThreadLocal 上下文）

```java
public class TenantContext {
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();
}
```

**ThreadLocal 是什么？**
每个线程有一个独立的变量副本。Java Web 应用中，一个请求由一个线程处理。所以 ThreadLocal 可以用来在同一个请求的不同方法之间传递数据，不同请求之间互不干扰。

**三个方法：**

| 方法 | 谁调用 | 做了什么 |
|------|--------|---------|
| `set(tenantId, userId, role)` | TenantInterceptor | 把 JWT 里的信息存入 ThreadLocal |
| `getTenantId()` | KnowledgeSearchTool 等业务代码 | 读取当前租户 ID |
| `clear()` | TenantFilter finally 块 | 请求结束清空，防止内存泄漏 |

**为什么必须 clear？**
Web 服务器使用线程池，请求处理完线程不销毁而是归还池中。如果不 clear，下一个复用到这个线程的请求会读到上一个请求的租户信息——严重的安全漏洞。

---

#### JwtTokenProvider.java（JWT 签发与验证）

**JWT 是什么？**
JSON Web Token，一种在各方之间安全传输信息的紧凑格式。由三部分组成：

```
header.payload.signature
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyXzAwMSIsInRlbmFudElkIjoidGVuYW50XzAxIn0.xxxxx
```

**Token 里存了什么：**

```json
{
  "sub": "user_001",        // 用户 ID（标准字段）
  "tenantId": "tenant_01",  // 租户 ID（自定义字段）
  "role": "USER",            // 角色（自定义字段）
  "iat": 1721300000,        // 签发时间
  "exp": 1721386400         // 过期时间（24 小时后）
}
```

**方法：**

| 方法 | 说明 |
|------|------|
| `generateToken(userId, tenantId, role)` | 签发 JWT（用 HMAC-SHA256 签名）|
| `parseToken(token)` | 验证签名 + 解析内容，返回 Claims |
| `injectContext(Claims)` | 把 Claims 里的信息写入 TenantContext |
| `validateToken(token)` | 快速验证是否有效（不抛异常）|

**签名原理：**

```java
// 用密钥创建一个签名器
SecretKey key = Keys.hmacShaKeyFor(secret.getBytes());

// 签发
Jwts.builder()
    .subject(userId)
    .claim("tenantId", tenantId)
    .signWith(key)       // 用密钥签名
    .compact();

// 验证（用同一个密钥）
Jwts.parser()
    .verifyWith(key)     // 用同一个密钥验证
    .build()
    .parseSignedClaims(token)
    .getPayload();
```

密钥在 `application.yml` 中配置：`jwt.secret`，32 位以上。

---

#### TenantInterceptor.java（拦截器）

**拦截器是什么？**
Spring MVC 的概念，在请求进入 Controller 之前、之后执行代码。可以理解为"中间件"。

**执行时机：**

```
请求 → DispatcherServlet → TenantInterceptor.preHandle()
                          → Controller 方法
                          → TenantInterceptor.afterCompletion()
                          → 返回响应
```

**preHandle 逻辑：**

```java
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String authHeader = request.getHeader("Authorization");

    // ① 没有 Authorization 头 → 放行（不设租户信息）
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        return true;
    }

    // ② 有 Bearer token → 解析
    String token = authHeader.substring(7);  // 去掉 "Bearer "
    try {
        Claims claims = jwtTokenProvider.parseToken(token);
        jwtTokenProvider.injectContext(claims);  // → 写入 ThreadLocal
        return true;
    } catch (Exception e) {
        response.sendError(401, "Invalid token");
        return false;  // token 无效，拒绝请求
    }
}
```

**afterCompletion 逻辑：**

```java
public void afterCompletion(...) {
    TenantContext.clear();  // 清理 ThreadLocal
}
```

被拦截的路径在 `SecurityConfig.addInterceptors()` 中配置：
- 拦截：`/Love_app/**`
- 放行（不拦截）：`/auth/**`、`/tenant/token`、`/swagger-ui/**`、`/actuator/**`

---

#### TenantFilter.java（Filter）

**Filter 是什么？**
Servlet 容器级别的过滤器，比拦截器更底层。`OncePerRequestFilter` 保证每个请求只执行一次。

**为什么 Filter 和 Interceptor 都要？**

```
请求 → Filter（最外层）
     → Interceptor（第二层）
     → Controller
     → Interceptor.afterCompletion
     → Filter finally（最外层兜底清理）
```

双重保险：Interceptor 的 `afterCompletion` 在异常时可能不执行，Filter 的 `finally` 保证无论如何都会清理 ThreadLocal。

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)  // 最高优先级，最先执行
public class TenantFilter extends OncePerRequestFilter {
    protected void doFilterInternal(request, response, filterChain) {
        try {
            filterChain.doFilter(request, response);  // 执行后续流程
        } finally {
            TenantContext.clear();  // 无论如何都清理
        }
    }
}
```

---

#### SecurityConfig.java（安全配置）

**配置了三件事：**

```java
@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    // 1. 注册拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/Love_app/**")
            .excludePathPatterns("/auth/**", "/tenant/token", ...);
    }

    // 2. Spring Security 配置（放行所有）
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable())          // REST API 不需要 CSRF
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());  // 全部放行
        return http.build();
    }
}
```

**为什么引入 Spring Security 又全部放行？**
- 需要它的 `BCryptPasswordEncoder` 做密码加密
- 需要它的 filter chain 机制
- 真实的认证逻辑由 `TenantInterceptor` 处理，不需要 Spring Security 的 Authentication

---

#### User.java（MyBatis-Plus 实体）

```java
@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;             // 主键自动递增

    private String username;      // 用户名（唯一）

    private String password;      // BCrypt 加密后的密码

    @TableField("tenant_id")
    private String tenantId;      // 所属租户，默认 "default"

    private String role;          // USER（普通用户）| ADMIN（管理员）

    private Boolean enabled;      // 是否启用

    @TableField("created_at")
    private LocalDateTime createdAt;  // 创建时间（SQL 自动填充）
}
```

**MyBatis-Plus 注解：**

| 注解 | 作用 |
|------|------|
| `@TableName("users")` | 告诉 MP 这个类对应数据库的 `users` 表 |
| `@TableId(type = IdType.AUTO)` | 主键，自增 |
| `@TableField("tenant_id")` | 字段名和属性名不一致时映射 |

**角色体系：**
- `USER`：普通用户，只能使用系统
- `ADMIN`：管理员，后续可添加管理功能（如查看租户下用户列表）

---

#### UserMapper.java（MyBatis-Plus Mapper）

```java
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 不需要写任何方法，BaseMapper 已经提供了：
    // insert, deleteById, updateById, selectById, selectOne, selectCount, selectList...
}
```

**BaseMapper 提供的 CRUD：**

| 方法 | 对应 SQL |
|------|---------|
| `insert(user)` | `INSERT INTO users ...` |
| `selectById(id)` | `SELECT * FROM users WHERE id = ?` |
| `selectOne(wrapper)` | `SELECT * FROM users WHERE username = ?` |
| `selectCount(wrapper)` | `SELECT COUNT(*) FROM users WHERE username = ?` |
| `updateById(user)` | `UPDATE users SET ... WHERE id = ?` |
| `deleteById(id)` | `DELETE FROM users WHERE id = ?` |

`@Mapper` 让 Spring Boot 自动发现这个接口并创建代理实现类。

---

#### UserService.java（用户服务）

**负责两件事：注册 + 登录。**

**注册流程：**

```java
public String register(String username, String password, String tenantId, String role) {
    // ① 检查用户名是否已存在
    if (userMapper.selectCount(eq(username)) > 0) throw "用户名已存在";

    // ② 设置默认值
    if (tenantId == null) tenantId = "default";
    if (role == null) role = "USER";

    // ③ 密码 BCrypt 加密后存库
    userMapper.insert(new User(username, passwordEncoder.encode(password), tenantId, role));

    // ④ 返回 JWT（注册成功自动登录）
    return jwtTokenProvider.generateToken(username, tenantId, role);
}
```

**登录流程：**

```java
public String login(String username, String password) {
    // ① 查找用户
    User user = userMapper.selectOne(eq(username));
    if (user == null) throw new RuntimeException("用户名或密码错误");

    // ② 检查账号状态
    if (!user.getEnabled()) throw "账号已被禁用";

    // ③ 验证密码（BCrypt 匹配）
    if (!passwordEncoder.matches(password, user.getPassword())) throw "用户名或密码错误";

    // ④ 返回 JWT
    return jwtTokenProvider.generateToken(user.getUsername(), user.getTenantId(), user.getRole());
}
```

**BCryptPasswordEncoder 是什么？**
一种密码加密算法。同样的密码每次加密结果不同（加盐），不可逆向破解。`matches()` 方法验证明文和密文是否匹配。

---

#### AuthController.java（认证端点）

```java
@RestController
@RequestMapping("/auth")
public class AuthController {
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        try {
            String token = userService.register(username, password, tenantId, role);
            return Map.of("success", true, "token", token);
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        // 同上，调用 userService.login()
    }
}
```

请求格式（JSON body）：

```json
{"username": "admin", "password": "admin123", "tenantId": "tenant_01", "role": "ADMIN"}
```

`/auth/**` 路径在 `SecurityConfig` 中已被排除拦截，不需要 token。

---

#### TokenController.java（开发用签发端点）

```java
@RestController
public class TokenController {
    @GetMapping("tenant/token")
    public String generateToken(@RequestParam(defaultValue = "user_001") String userId,
                                 @RequestParam(defaultValue = "tenant_01") String tenantId,
                                 @RequestParam(defaultValue = "USER") String role) {
        return jwtTokenProvider.generateToken(userId, tenantId, role);
    }
}
```

**注意**：这个类只是开发测试用，生产环境应该走 `/auth/register` 和 `/auth/login`。

---

### 2.4 核心流程：一次带 token 的请求

以用户请求 `LoveManus` 为例：

```
① 用户请求
   GET /api/Love_app/chat/LoveManus?message=你好
   Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...

② TenantFilter.doFilterInternal()
      → 执行 filterChain.doFilter() 前不做任何事
      → 等待后续流程

③ TenantInterceptor.preHandle()
      → 提取 Authorization 头 → 拿到 token
      → JwtTokenProvider.parseToken(token)
          → 验证签名（用 jwt.secret）
          → 解析出 Claims: {sub: "user1", tenantId: "tenant_01", role: "USER"}
      → jwtTokenProvider.injectContext(claims)
          → TenantContext.set("tenant_01", "user1", "USER")

④ AiController.doChatWithLoveManus()
      → 调用 LoveManus.runStream("你好")

⑤ Agent 调用 searchKnowledge
      → KnowledgeSearchTool 读取 TenantContext.getTenantId() = "tenant_01"
      → milvusRetriever.search(query, topK, "tenant_01")
          → 过滤 tenant_id == "tenant_01"

⑥ TenantInterceptor.afterCompletion()
      → TenantContext.clear()

⑦ TenantFilter finally 块
      → TenantContext.clear()（双重保障）
```

### 2.5 核心流程：注册登录

**注册：**

```
用户 POST /auth/register {username, password, tenantId, role}
  → AuthController.register()
  → UserService.register()
      → UserMapper.selectCount(eq("username")) → 检查重复
      → new User(username, BCrypt(password), tenantId, role)
      → UserMapper.insert(user) → 存入 MySQL
      → JwtTokenProvider.generateToken(username, tenantId, role) → 签发 JWT
  → 返回 {success: true, token: "eyJ..."}
```

**登录：**

```
用户 POST /auth/login {username, password}
  → AuthController.login()
  → UserService.login()
      → UserMapper.selectOne(eq("username")) → 查用户
      → passwordEncoder.matches(password, user.getPassword()) → 验证密码
      → JwtTokenProvider.generateToken(username, tenantId, role) → 签发 JWT
  → 返回 {success: true, token: "eyJ..."}
```

---

## 三、两个包的交互关系

```
tenant 包                         retrieval 包
─────────                         ──────────
TenantContext                   KnowledgeSearchTool
  ├── getTenantId() ─────────────→ ├── 传给 HybridRetrievalService.search(query, k, tenantId)
  │                                 ├── 传给 MilvusVectorRetriever.search(query, k, tenantId)
  │                                 └── 传给 ESKeywordRetriever.search(query, k, tenantId)
  │
JwtTokenProvider                HybridRetrievalService
  ├── generateToken()              ├── 用 tenantId 调用上面两个 search
  └── parseToken()                 └── 传给 RRFFuser.fuse()

TenantInterceptor               PgVectorVectorStore
  ├── 解析 token → 注入             └── 用 tenantId 拼 filterExpression
  └── 清理 ThreadLocal

SecurityConfig
  └── 放行 auth 端点
```

简单说：**tenant 包提供"当前是谁"的信息，retrieval 包用这个信息做数据隔离。**

---

## 四、配置对照表

### application.yml 中的相关配置

```yaml
# 检索模式（retrieval 包）
rag:
  type: hybrid                          # pgvector | hybrid

# 租户+JWT（tenant 包）
jwt:
  secret: lwx-ai-agent-secret-key-32-chars  # JWT 签名密钥
  expiration-ms: 86400000                   # Token 过期时间（24h）

# MyBatis-Plus
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
```

### application-local.yml 中的相关配置

```yaml
# 检索用后端地址
rag.hybrid.es.uris: http://172.28.23.117:9200
rag.hybrid.milvus.host: 172.28.23.117
rag.hybrid.milvus.port: 19530
```

---

## 五、常见问题

### Q: 无 token 的请求能正常使用吗？

能。`TenantInterceptor.preHandle()` 检测到没有 Authorization 头就放行，`TenantContext.getTenantId()` 返回 null。检索时不过滤，查全部数据。完全兼容升级前的行为。

### Q: 怎么创建管理员用户？

```bash
curl -X POST http://localhost:8088/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","tenantId":"tenant_01","role":"ADMIN"}'
```

### Q: 不同租户的数据怎么隔离的？

三种存储各自的方式：

| 存储 | 过滤方式 |
|------|---------|
| PGvector | `SearchRequest.filterExpression(eq("tenantId", id))` |
| Milvus | `SearchReq.filter("tenant_id == \"id\"")` |
| ES | `bool.filter(term("tenant_id", id))` |

### Q: 现有的 37 条文档是哪个租户的？

默认 `tenantId = "default"`。在 `LoveAppDocumentLoader.loadMarkdowns()` 中给每个文档加上了 `tenantId=default`。

### Q: 为什么 Milvus 搜索返回空？

可能原因：
1. Milvus 没启动 → 看启动日志是否有 `Connected to Milvus`
2. Collection 没数据 → 看 `MilvusDocumentInitializer` 日志
3. 租户过滤条件太严 → `tenantId` 不匹配
4. 向量维度不匹配 → 看 `Embedding model actual dimension` 日志

### Q: RRF 的 k 值怎么调？

```yaml
rag:
  hybrid:
    rrf:
      k: 60    # 候选量大用 60，量小用 30
      topK: 10 # 最终返回条数
```

经验：上线前拿真实数据跑对比，选效果最好的 k 值。

### Q: 注册时可以不传 tenantId 吗？

可以。不传时自动分配 `tenantId = "default"`，进入单租户模式。

### Q: JWT 过期了怎么办？

默认 24 小时过期。目前不做 refresh token，过期后需要重新登录。可以改 `jwt.expiration-ms` 调整过期时间。
