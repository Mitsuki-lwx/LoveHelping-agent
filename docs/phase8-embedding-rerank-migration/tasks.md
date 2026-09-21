# tasks.md — RAG embedding 与 rerank 迁移到硅基流动

> **背景**：本机 Clash 处于 `global` 模式，`dashscope.aliyuncs.com` / `api.sensenova.cn`
> 的 TLS 被阻断（DNS 解析到 fake-ip `198.18.0.x`），导致 RAG 检索链路全 500
> （日志 70 次失败 / 0 次成功）。需将 embedding 与 rerank 迁到可达且质量不降的厂商。
>
> **选型依据**（2026-09-20 离线评测，45 用例 ground truth，详见
> `.workbuddy-ai/memory/2026-09-20.md`）：
> - embedding 选 `Qwen/Qwen3-Embedding-0.6B`：1024 维**与现有 `vector(1024)` 兼容**，
>   439 块全量嵌入约 **30s**（bge-m3 质量更高但频繁 read timeout，需 20+ 分钟 → 弃）。
> - rerank 选 `Qwen/Qwen3-Reranker-8B`：两种 embedding 下**一致最优**
>   （bge-m3 上 0.8285→0.9111，Qwen0.6B 上 0.7026→0.8741），且**推翻**了旧结论
>   「rerank 负收益」（那只对项目原有的本地 MiniLM 成立）。
>
> **成本**：单次 rerank 1690 tokens，按最贵 Pro 档 ¥0.07/M 折算日 1000 次 ≈ **¥3.55/月**，
> 16 元额度可用 135 天 → **成本不是约束**。
>
> **前置约束**：`spring.ai.openai.*` 已被智谱占用（`open.bigmodel.cn`，主聊天），
> 且其 `embedding.enabled=false`。**硅基流动必须独立配置，不复用该通道。**

---

## Task 1：新增硅基流动 embedding 客户端

**目标**：提供一个 OpenAI 兼容协议的 `EmbeddingModel`，指向硅基流动，维度 1024。
**依赖**：无
**影响文件**：
- 新增 `config/SiliconFlowEmbeddingConfig.java`（或纳入现有 `EmbeddingModelConfig`）
- 新增 `config/SiliconFlowProperties.java`（`app.siliconflow.base-url/api-key/embedding-model/dimensions`）
- `src/main/resources/application.yml`（新增 `app.siliconflow` 块，api-key 走环境变量）
- `target/classes/application-local.yml`（本地覆盖，**gitignore 不提交**）

**内容**：
- 用 `OpenAiApi` + `OpenAiEmbeddingModel` 手工构造（**不复用自动配置**，避免与智谱的
  `spring.ai.openai` 冲突）；base-url `https://api.siliconflow.cn`，completions/embeddings path 默认
- Bean 名沿用 `dashscopeEmbeddingModel`？**否** —— 改为语义中性的新 bean 名，
  通过 `@Primary` 切换，保留旧 bean 以便回滚（见 Task 5）
- 维度 1024，与 `vector(1024)` 一致，**无需改表**

---

## Task 2：新增硅基流动 rerank 客户端

**目标**：实现 `DocumentReranker`，调用 `POST /v1/rerank`。
**依赖**：无
**影响文件**：
- 新增 `rag/rerank/SiliconFlowDocumentReranker.java`（implements `DocumentReranker`）
- `rag/rerank/RerankProperties.java`（`mode` 增加 `remote`/`siliconflow` 取值；
  新增 `apiKey`/`model`/`baseUrl` 字段）
- `src/main/resources/application.yml`（`app.rag.rerank` 增字段）

**内容**：
- 请求体：`{"model": "Qwen/Qwen3-Reranker-8B", "query": q, "documents": [...], "top_n": n}`
- 响应解析：`results[].index` + `relevance_score`，**按 score 降序**重排，映射回原候选
- **index 越界/缺失必须回退原序且不丢文档**（沿用 `LlmDocumentReranker` 的防御范式）
- 失败（超时/HTTP 非 200/解析异常）→ **降级为原顺序前 K**，记日志，不抛错中断 RAG
- 候选文本裁剪复用 `maxCandidateChars`（默认 1000）
- 超时默认相应放宽（实测 8B 偶发 3.97s，**timeoutMs 默认 5000**，非现有 2000）

---

## Task 3：配置切换与回滚开关

**目标**：让"用哪套 embedding / rerank"是**运行时决策**，可一键回滚。
**依赖**：Task 1、Task 2
**影响文件**：
- `src/main/resources/application.yml`
- `config/EmbeddingModelConfig.java`（`@Primary` 的选取逻辑）

**内容**：
- 新增 `app.rag.embedding.provider`：`siliconflow`（默认）/ `dashscope`（回滚）
- `app.rag.rerank.mode`：`off`（默认，保守）/ `siliconflow` / `local`（旧，回滚）
- **默认值**：embedding 用 siliconflow（因为 DashScope 已不可用）；
  rerank **保持 `off`** —— 开它要额外网络调用，先在冒烟里验证再加
- 所有密钥**只走环境变量**，`application.yml` 里留空占位

---

## Task 4：全量重建向量库

**目标**：把 439 个知识库块用新 embedding 重新嵌入并写入 `vector_store`。
**依赖**：Task 1、Task 3
**影响文件**：
- 复用现有重建入口（`DocumentLoader` / 索引组件；若需脚本则新增 `scripts/reindex.py`）
- `vector_store` 表数据（**破坏性操作**）

**内容**：
- **重建前必须备份**：`pg_dump` 导出 `vector_store`（含旧向量），确认文件可读
- **必须过滤 `source=memory|evolution`**：784 行里 345 行是用户记忆，**不是知识库**；
  重建只处理知识库块（439 行），**不得误删用户记忆**
- 新旧向量**语义空间不同，必须全量重嵌入**，不能只做增量
- 重建后校验：行数、维度、非空向量比例

---

## Task 5：回滚路径

**目标**：迁移失败时能退回原状态。
**依赖**：Task 4
**影响文件**：无（流程与数据）

**内容**：
- 配置回滚：`app.rag.embedding.provider=dashscope` + `app.rag.rerank.mode=off`
- 数据回滚：用 Task 4 的 `pg_dump` 备份恢复 `vector_store`
- 记录备份文件路径与恢复命令到本文档

---

## Task 6：端到端验证

**目标**：证明链路真的通了，且检索质量不降。
**依赖**：Task 4
**影响文件**：无

**内容**：
- 启动应用（真启动），确认无 `SSLHandshakeException`、无 embedding 报错
- `GET /admin/rag/retrieve` 逐条跑 45 用例，对比 MRR@5
- SSE 真发一条聊天请求，确认检索有命中、回答正常
- 单测全绿

---

## Task 7：文档同步

**目标**：让文档不撒谎。
**依赖**：全部
**影响文件**：
- `docs/03-技术决策记录.md`（新增 ADR：embedding/rerank 迁移）
- `docs/02-架构设计.md`、`04-数据模型设计.md`（如有表述变化）
- `.workbuddy/memory/MEMORY.md`（更新第 44 行 rerank 结论）
