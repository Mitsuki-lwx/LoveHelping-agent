# spec.md — RAG embedding 与 rerank 迁移到硅基流动

> **对应**：`tasks.md`（Task 1–7）、`checklist.md`（验收清单）
> **日期**：2026-09-21
> **状态**：待评审

---

## 1. 问题陈述

### 1.1 现象
本机启动应用后，RAG 检索链路**全部 500**。日志显示 70 次失败、0 次成功检索：
```
javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
```

### 1.2 根因（已定位，非推测）
`dashscope.aliyuncs.com` / `api.sensenova.cn` 的 DNS **被解析到 `198.18.0.x`（fake-ip）**。
现场：`clash-verge.exe` + `verge-mihomo.exe` 在跑，`config.yaml` 中 **`mode: global`**
（全局模式，无分流规则），`tun.dns-hijack: [any:53]`，`dns.enhanced-mode: fake-ip`。

**判据特征**：TCP 通（0.013s）但 TLS 断（0.000s）。

> ⚠️ 此前我判为「DashScope 侧不可达」是**不够准的** —— 真实原因是**本机代理配置**。
> 走代理端口 7897 同样失败，说明节点本身到阿里云 TLS 不通。

### 1.3 未选择的方案
**把 Clash 切回 `rule` 模式**可能让 DashScope 直接恢复、整条迁移都不必做。
但代理是用户环境，**未擅自修改**。迁移方案在两种模式下都成立，故先行。

---

## 2. 现有架构与改造点

| 组件 | 现状 | 改造 |
|---|---|---|
| `dashscopeEmbeddingModel` | DashScope 提供，1024 维 | 换为硅基流动 `Qwen/Qwen3-Embedding-0.6B`（1024 维） |
| `PgVectorVectorStore` | `@Qualifier("dashscopeEmbeddingModel")` **硬绑定** | 改为绑定新 bean（或让 `@Primary` 生效） |
| `ParentChildDocumentRetriever` | 注入 `@Qualifier("dashscopeEmbeddingModel")` | 同上 |
| `SkillIngestor` | 注入 `@Qualifier("dashscopeEmbeddingModel")` | 同上 |
| `LocalDocumentReranker` | 本地 8091 端口 MiniLM | 新增 remote 实现（见 §4） |
| `spring.ai.openai.*` | **已被智谱占用**（`open.bigmodel.cn`），`embedding.enabled=false` | **不复用**，独立配置 |

### 2.1 关键约束：不能复用 `spring.ai.openai`
`spring.ai.openai` 已被智谱用于主聊天（`glm-4-flash`），且其 `embedding.enabled=false`。
往同一前缀下写硅基流动的 key 会**互相覆盖**。

**方案**：独立配置前缀 `app.siliconflow.*` + 独立 bean，**不经过自动配置**。

> **实施修正（2026-09-21，写码时定案）**：本 spec 原写"手工构造 `OpenAiApi` + `OpenAiEmbeddingModel`"，
> **实际实现改为直接手写 `EmbeddingModel` 实现类**（`SiliconFlowEmbeddingModel`，用 JDK `HttpClient`）。
>
> 理由：`OpenAiEmbeddingModel` 的构造需要 `OpenAiApi` → `WebClient` 一整套 Spring AI 内部装配，
> 而本机已实测过**框架 WebClient 对 OpenAI 兼容端点返回 404 的既存问题**
> （见 `ChatModelConfig` 中 `deepSeekFallbackModel` 的注释：
> "OpenAI 兼容 /v1 型网关对框架 WebClient 返回 404（curl 同 URL 200）"）。
> 用同一套 WebClient 装配去调硅基流动，很可能**复现同一个 404**。
>
> 手写实现还带来两个好处：①维度、条数、每条形能做**显式校验**（`OpenAiEmbeddingModel`
> 不会校验维度是否等于 1024，会直接把 2560 维写进 `vector(1024)` 列并报出难懂的 JDBC 错误）；
> ②错误信息可直接带上上游响应片段，便于排查。
>
> 此前评测阶段调硅基流动全部用的是**裸 HTTP**（`urllib` / `node fetch`），
> 走的是同一个路径，协议已被 45 用例实测验证 —— 手写实现与已验证路径一致，风险更低。

### 2.2 维度一致性
`vector_store.embedding` 是 `vector(1024)`。`Qwen/Qwen3-Embedding-0.6B` 输出 **1024 维（实测）**
→ **无需改表**。（`bge-m3` 同为 1024；4B=2560、8B=4096 均不兼容，已排除。）

---

## 3. Task 1：embedding 客户端

### 3.1 配置项
```yaml
app:
  siliconflow:
    base-url: ${SF_BASE_URL:https://api.siliconflow.cn}
    api-key: ${SF_API_KEY:}            # 只走环境变量，不落盘
    embedding-model: ${SF_EMB_MODEL:Qwen/Qwen3-Embedding-0.6B}
    timeout-ms: ${SF_TIMEOUT_MS:10000} # 首次冷启动实测 8.5s（bge-m3），Qwen 约 0.3s，留足余量
```
```yaml
app:
  rag:
    embedding:
      provider: ${RAG_EMBEDDING_PROVIDER:siliconflow}   # siliconflow | dashscope（回滚）
```

### 3.2 Bean 策略
- 新增 bean 名 `ragEmbeddingModel`（语义中性，不再叫 dashscope）
- `config/EmbeddingModelConfig` 的 `@Primary` 按 `provider` 选取：
  - `siliconflow` → `ragEmbeddingModel`
  - `dashscope` → `dashscopeEmbeddingModel`（旧 bean 保留，供回滚）
- `PgVectorVectorStoreConfig` / `ParentChildDocumentRetriever` / `SkillIngestor`
  的 `@Qualifier` **改为 `ragEmbeddingModel`**，或去掉 `@Qualifier` 让 `@Primary` 生效
  → **决策：改为显式注入 `@Qualifier("ragEmbeddingModel")`**，理由是不依赖 `@Primary` 的隐式行为，
  改动可读、回滚明确

### 3.3 失败语义
embedding 失败 → **抛出**（与现状一致）。不静默降级，因为检索没有向量就无意义。

---

## 4. Task 2：rerank 客户端

### 4.1 关键发现：协议已兼容
现有 `LocalDocumentReranker` 的请求/响应格式**与硅基流动 `/v1/rerank` 完全一致**：

| | 现有实现 | 硅基流动 |
|---|---|---|
| 请求 | `{query, documents, top_n}` | `{model, query, documents, top_n}` |
| 响应 | `results[].{index, relevance_score}` | `results[].{index, relevance_score}` |

**→ 改造量极小**：新增 `model` 字段 + `Authorization` 头 + 可配置 endpoint/超时。

### 4.2 实现方式
**决策：不新建类，扩展 `LocalDocumentReranker` 为通用 HTTP reranker。**
- 理由：两者协议一致、防御逻辑（index 越界/去重/非有限分数/降级）已成熟，
  复制一份会造成逻辑分叉（后续修复要改两处）。
- 改动：`RerankProperties` 增 `apiKey` / `model` / `authHeaderEnabled`；
  构造时按 `apiKey` 是否为空决定是否加 `Authorization` 头。
- `mode` 取值新增 `remote`（`local` 保留语义=无鉴权的本地服务）。

### 4.3 超时（必须放宽）
实测 8B 常态 0.42s，但**偶发 3.97s**。现有 `timeoutMs` 默认 **2000 会误杀**。
→ `app.rag.rerank.timeout-ms` **默认改为 5000**（`local` 模式下可保持 2000，
但统一 5000 更简单且本地服务响应更快，无副作用）。

### 4.4 失败语义（沿用现有）
失败 → `throw`，由 `RerankDocumentPostProcessor` 降级为原顺序。
**不 fallback 到付费 LLM**（现有注释明确要求）。

### 4.5 默认值（保守）
```yaml
app:
  rag:
    rerank:
      enabled: ${RERANK_ENABLED:false}   # 保持关闭
      mode: ${RERANK_MODE:local}
      model: ${RERANK_MODEL:Qwen/Qwen3-Reranker-8B}
```
**理由**：开 rerank 会引入一次网络调用（+0.4s 且偶发 4s）。虽然评测证明质量提升，
但**先冒烟验证链路通、再单独开**，避免一次改两个变量。

---

## 5. Task 4：全量重建

### 5.0 策略修正：改为「原地重嵌入」（实施时定案，2026-09-21）

原计划是"重新加载 markdown → 重新切分 → 全量嵌入"。**读码后改为原地重嵌入**。

**触发这个修正的事实**：`ParentChildDocumentTransformer` **并非父子链切分器**，
而是 **overlap 扁平切块**（`TARGET=400` / `OVERLAP=80`），其 javadoc 明确写着
"父子为过度设计"（2026-09-05 两轮对照评测后的结论）。同时库中 439 块已带完整 metadata：
`filename` / `chunk_index` / `doc_hash` / `chunk` / `category` / `status` / `tenantId`。

**两种做法对比**：

| 维度 | 重新加载 + 重新切分 | **原地重嵌入（采用）** |
|---|---|---|
| 切分算法风险 | 有（须逐字复刻，否则块数/文本漂移） | **无**（不切分） |
| `doc_hash` | 可能变 → 触发下次启动的增量重建 | **不变** |
| 块文本 | 可能变 → 评测不可归因（换模型 vs 换切分混杂） | **不变** |
| 块数守恒 | 需额外验证 | **天然满足** |
| 用户记忆安全 | 需小心处理 | 高（只 UPDATE 指定行） |

代价是绕过了 `ParentChildDocumentTransformer`。但既然它已是扁平 overlap 切块，
"重新切分"不会产出不同结果 —— 这个绕过没有实际损失，反而消除了不确定性。

**实现**：`scripts/reembed_knowledge_base.py`，只 `UPDATE vector_store SET embedding`。

### 5.1 必须遵守
1. **先备份**：`pg_dump` 导出 `vector_store`（含旧向量）。备份不成功**不得继续**。
2. **只重建知识库块**：784 行中 **345 行 `source=memory`（用户对话记忆）**，
   **不是知识库**。生产检索本就过滤它们（双轨收敛）。
   **重建脚本必须同样过滤，绝不能删用户记忆。**
3. **全量重嵌入**：新旧向量语义空间不同，**增量更新无意义**。
4. 知识库块数应为 **439**（与启动日志 `131 docs -> 439 child chunks` 吻合）。
5. **只 UPDATE，不 DELETE、不 INSERT**：脚本的 `WHERE` 子句显式排除
   `source IN ('memory','evolution')`；脚本收尾带断言，不满足即非零退出。
6. 维度非 1024 **即拒**：不能把 2560/4096 维写进 `vector(1024)` 列
   （那会变成难懂的 JDBC 错误，且可能留下半写状态）。

### 5.2 校验
- 行数：知识库块 = 439（±0，若文档有变则以实际为准并记录）
- 维度：`vector_dims(embedding) = 1024`
- 非空：无 NULL embedding
- **用户记忆行数不变**（重建前后比对，只增不减）
- 总行数不变（784）

### 5.3 恢复演练（已执行，2026-09-21）
**"备份文件非空"不等于"能恢复"**。已做真实演练：临时库 `backup_drill` +
`pg_restore` + **全表逐行指纹比对**，结果 `MATCH`。

**演练暴露的真实陷阱（必须记录）**：向**空库**恢复前必须先安装 4 个扩展，否则
`CREATE TABLE` 失败、后续全部级联失败：
```sql
CREATE EXTENSION IF NOT EXISTS vector;         -- 0.8.1，提供 vector(1024) 类型
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";    -- 1.1，提供 uuid_generate_v4()
CREATE EXTENSION IF NOT EXISTS pg_trgm;        -- 1.6，GIN 三元组索引
CREATE EXTENSION IF NOT EXISTS hstore;         -- 1.8
```
（注意：`pg_dump -t <table>` 不含扩展定义，扩展是**库级**对象。）

---

## 6. Task 6：端到端验证

| 项 | 判据 |
|---|---|
| 编译 | `mvn` 编译通过（见 skill `lwx-ai-agent-verify-push` 的 mvn 调用范式） |
| 单测 | 全绿（基线 247/247） |
| 真启动 | 无 `SSLHandshakeException`，无 embedding 报错 |
| 检索 | `GET /admin/rag/retrieve` 跑 45 用例，MRR@5 **不低于旧基线**（旧 DashScope 口径 0.8352，但口径不同，以**同口径复测**为准） |
| 聊天 | SSE 真发请求，有检索命中 + 正常回答 |
| rerank | 开启后 `rag_rerank_*` 指标出现，响应含重排效果 |

---

## 7. 回滚

```bash
# 配置回滚（两个环境变量即可）
RAG_EMBEDDING_PROVIDER=dashscope
RERANK_MODE=local
```
```bash
# 数据回滚
psql -U postgres -d postgres -c "TRUNCATE vector_store;"   # ⚠️ 会清记忆，先确认备份
psql -U postgres -d postgres < <backup-file>.sql
```
**备份文件路径记录于 checklist 验收时填写。**

---

## 8. 风险与未决

| 风险 | 影响 | 缓解 |
|---|---|---|
| 硅基流动 API 不稳定（实测 bge-m3 频繁 timeout） | 重建时间不可控 | Qwen0.6B 实测稳定（30s）；重试+二分降级 |
| 供应商锁定 | 换厂商要再迁移 | 用标准 OpenAI 兼容协议，接口抽象已存在 |
| 8B rerank 偶发 3.97s | 用户等待 | 默认关闭；超时 5000ms；失败降级原序 |
| **密钥落盘** | 安全 | 只走环境变量；`application-local.yml` gitignore |
| 免费额度政策变化 | 突然收费/限流 | 已测算 ¥3.55/月上限，成本非约束 |
| 重建误删用户记忆 | **数据丢失** | 强制过滤 `source=memory`；重建前后比对行数 |

---

## 9. 实测结果与交付缺陷（2026-09-21 收尾轮补记）

> 本节由**另一轮会话**在拿到 `SF_API_KEY` 后补写：完成 Task 4、跑完 Task 6，并**发现两个真缺陷**。

### 9.1 Task 4 执行结果

| 项 | 值 |
|---|---|
| 知识块重嵌入 | **439 行 / 16.7s（26.3 块/s）**，`--scope knowledge` |
| 用户记忆重嵌入 | **385 行 / 12.5s（30.7 块/s）**，`--scope memory`（见 §9.3，**必须做**） |
| `total / knowledge / memory / null_emb / dims` | 守恒 / 439 / 385 / 0 / 1024 |
| content 指纹（前后） | **一致**（只改了 `embedding` 列） |

### 9.2 缺陷一：`RERANK_MODE=remote` **根本没接到硅基流动**（接线层）

`RerankDocumentPostProcessor` 里原本写的是：

```java
List<Document> ranked = ("local".equals(properties.getMode()) ? local : llm).rerank(...);
```

于是 `mode=remote` 被**静默路由到 `LlmDocumentReranker`**（走主线模型 `chatModel.call()` 打分），
硅基流动 `/v1/rerank` **一次都没被调用**。而：

- `LocalDocumentReranker` 的 remote 分支写得很完整（`model` 字段、`Bearer` 鉴权、https 强制、缺 key 构造即抛错）；
- `LocalDocumentRerankerTest` 的 `remoteMode_requestBody_hasModelAndAuth` **全绿**；
- 配置回显、启动日志一切正常。

**为什么没被发现**：单测覆盖了**组件**，没有覆盖**组件之间的连线**。
`LocalDocumentReranker` 在容器里只被后处理器当作 `local` 使用，remote 走的却是另一个 bean。

**代价（实测）**：在 `mode=remote` 下测出的"重排收益"其实是 **glm-flash** 的收益 ——
MRR 0.776（伪 remote）vs **0.856**（真 8B，三轮中位）。**假引擎把真引擎的效果吞掉了 0.08。**

**修复**：`DocumentReranker engine = "llm".equals(properties.getMode()) ? llm : local;`
（`local` 与 `remote` 是同一个 HTTP 实现的两种配置）。
新增 `RerankDocumentPostProcessorTest`（6 例）专门守接线；**对照实验**：还原旧接线 →
`remoteMode_usesHttpReranker_notLlm` **精确失败且只它一个**。
端到端证据：8091 关闭后，日志出现 `Rerank call -> https://api.siliconflow.cn/v1/rerank` 与 `Rerank ok <-`。

> 📌 **模式小结**：这不是"实现写错了"，而是**"实现是对的，只是没接上"**。
> 判定特征是——**功能静默走错分支、单测全绿、只有配置回显没有调用实录**。
> 本轮顺带给 `LocalDocumentReranker` 补了 INFO 配置回显 + DEBUG 每调用一行（此前**一行日志都没有**，
> 导致"走了本地还是远端"从日志上无法回答）。

### 9.3 缺陷二：只重嵌入知识块 → **用户记忆检索静默失效**（且连知识检索一起污染）

`MemoryVectorStore.searchMemory()` 是**纯向量相似度**（`vectorStore.similaritySearch`）。
若只重嵌入知识块，375 行用户记忆仍是旧厂商向量 → 与查询向量**近似正交**（实测 cos ≈ −0.014）：

```
查询(用记忆自己的原文) '用户寻求非暴力沟通…'
  最近 15 条 = memory 0 条 / 知识块 15 条
  过滤 userId 后实际能返回 = 0 条      ← 不报错、返回空，完全静默
对照：记忆之间在旧空间自相似  1.0000 / 0.9419 / 0.9229   ← 旧向量本身没坏，坏的是跨空间
```

**连带的第二层影响**（当时未察觉）：`ParentChildDocumentRetriever.hybridRetrieve` 的向量召回
**SQL 里没有源过滤**（取 `topK*3` 后融合、再按源过滤），因此这些跨空间向量**也在挤占知识检索的位置**。
修复后基线 MRR **稳定为 0.751 × 3 轮**；修复前那一轮记的是 0.762 —— 也就是说 **0.762 是污染下的读数**。

**修复与验证**：`--scope memory` 重嵌入 385 行（12.5s），content 指纹不变；
最近 15 条变为 **memory 15 / 其他 0**，同 `userId` 可返回，top1 cos **0.9999**；
随机抽 6 行（含 knowledge 与 memory）对比新模型现算向量，`cos ≥ 0.9998`。

> ⚠️ **原风险表的"重建误删用户记忆 → 强制过滤 `source=memory`"这条缓解措施是必要但不充分的**：
> 它守住了**数据**，却破坏了**功能**。**"不碰用户数据" 与 "不破坏用户功能" 是两件事**，
> 后者需要一条独立检查：*凡是被排除在重嵌入之外的行，如果它们参与向量检索，就必然失效*。

### 9.4 检索质量（45 例，同一后端，多轮 —— 按 phase9 §S5 的噪声纪律）

| 配置 | Recall@5 | MRR@5 | 备注 |
|---|---|---|---|
| 迁移前：dashscope 向量，无重排 | 0.93 | **0.807** | phase9 记录，**单轮** |
| 迁移后：硅基流动向量，无重排 | 0.93 | **0.751 × 3 轮（三轮完全一致）** | 纯向量+hybrid，无随机性 |
| 迁移后 + **真·远端 8B 重排** | 0.93 | **0.856 / 0.844 / 0.856（中位 0.856）** | 本轮唯一可确证的正收益 |
| （伪对照）迁移后 + glm-flash 重排 | 0.91 | 0.776 | §9.2 的接线 bug 产物 |

**结论**：

1. **重排是明确正收益**：**+0.105 MRR**（0.751 → 0.856），
   而 phase9 实测的**噪声带是 0.012**（同一配置两轮的自然差）→ 该效应远超噪声，且三轮方向一致。
   Recall 不变（0.93），说明重排只改善**排序**、不改变**召回集合**。
2. **"换成硅基流动 embedding"本身没有可确证的收益**：0.807（单轮、dashscope）→ 0.751（三轮）。
   但**这不是干净对照**（见 §9.5 未验证项），既不能断言变差、也不能断言持平。
3. 重排的延迟代价：热态 **0.39~0.43s**（冷启动首调用 5.21s，且 >`timeout-ms=5000` → **冷启动有被超时误杀的风险**）。

### 9.5 未验证 / 局限（**报忧不报喜**）

- ❌ **缺 dashscope 侧的"同轮次、同时期"对照**。`0.807` 是 phase9 的**单轮**读数，
  而 `0.751` 是本轮三轮读数 —— 两者不同期、不同轮次口径。要判"换 embedding 是否更好"，
  需把备份还原到**旁库**、以 `RAG_EMBEDDING_PROVIDER=dashscope` 指过去跑同样的 3 轮。
  **本轮没做**，因此 §9.4 结论 2 只写"无法确证"，没写"变差"。
- ❌ **embedding 会不会因这批数据变更而变好/变坏，未做人工抽检**（只看聚合指标）。
- ❌ **8B 重排冷启动 5.21s 超过 `timeout-ms=5000`**：只测到一次，未复现；未做预热或调超时。
- ❌ **重排的成本与限流**未实测（spec 里 ¥3.55/月是**推算**，不是账单）。
- ❌ **未测多实例**：熔断器 `ProviderCircuit` 与 `maxConcurrent=2` 都是**进程内**状态。
- ❌ 记忆行重嵌入后**未跑一遍"跨会话记忆召回"的真实多轮对话 E2E**（只做了向量层面验证）。
- ⚠️ **默认值本轮未改**：`app.rag.rerank.enabled` 仍 `false`、`mode` 仍 `local`。
  是否改成 `enabled=true / mode=remote` 需你拍板 —— 依据见 §9.4（MRR +0.105，代价是每查询多一次外部调用）。
