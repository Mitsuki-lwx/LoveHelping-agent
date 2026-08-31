# tasks.md — RAG 阶段4+：检索重排（Rerank）实现任务

> **前置**：ADR-15（阶段 4 落地记录随本套件附加）。当前 RAG 链路：`ParentChildDocumentRetriever`（topK=5，子→父）→ `RetrievalAugmentationAdvisor`（含 QueryRewriter）。
> 参考：`RetrievalAugmentationAdvisor` 的 `documentPostProcessors(...)` builder 参数、`org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor`、现有 `QueryRewriter`（ChatModel 注入）。

---

## Task 1：DocumentReranker 接口 + 配置

**目标**：定义可插拔重排器接口与配置项。
**依赖**：无
**影响文件**：
- 新增 `rag/rerank/DocumentReranker.java`（接口：`List<Document> rerank(String query, List<Document> candidates, int topK)`）
- 新增 `rag/rerank/RerankProperties.java`（`app.rag.rerank.enabled/topN/topK/mode`，@ConfigurationProperties）

**内容**：
- 接口语义：对候选文档打分/排序，返回前 K；实现可能不依赖 Spring（未来 bge-reranker 客户端也实现它）
- 默认值：enabled=false（先关后开，灰度）、topN=50、topK=5、mode=llm
- topN 小于等于 topK 时不重排（退化，防御）

---

## Task 2：LLM 重排 v1（LlmDocumentReranker）

**目标**：主线模型打分重排，零新依赖。
**依赖**：Task 1
**影响文件**：
- 新增 `rag/rerank/LlmDocumentReranker.java`（@Component，注入主模型 = LlmGateway）

**内容**：
- 构造重排 prompt：给 query + 编号候选列表（前 N 条截断，控 token），要求只返回按相关性排序的前 K 个编号及一句理由
- 解析：提取编号 → 保序映射回候选文档；编号非法/缺失 → 回退原顺序拼上缺位（**不丢文档**）
- 输出前 K 候选；candidates ≤ topK 时直接返回（不调 LLM）
- 失败（解析异常/模型错误）→ 降级为原顺序前 K，记录日志（不抛错中断 RAG）

---

## Task 3：挂载为 DocumentPostProcessor

**目标**：把重排缝进现有 RAG advisor 的 postretrieval 阶段。
**依赖**：Task 1、Task 2
**影响文件**：
- 新增 `rag/rerank/RerankDocumentPostProcessor.java`（implements DocumentPostProcessor）
- `rag/ParentChildDocumentRetriever.java`（topK = `app.rag.rerank.topN`，默认 50）
- `rag/RagAdvisorConfig.java`（rerank.enabled 时给 advisor 加 `documentPostProcessors(rerankPostProcessor)`）

**内容**：
- RerankDocumentPostProcessor.apply(query, List<Document>)：rerank.enabled && mode=llm 时调用 LlmDocumentReranker，否则原样返回
- 裁剪过大的候选文本传给 LLM（如每候选 300 字），防 token 爆
- 关闭时链路与现状完全一致（postprocessor 原样透传）

## Task 4：配置接入 application.yml + 指标

**目标**：开关落地 + 观测。
**依赖**：Task 3
**影响文件**：
- `src/main/resources/application.yml`（`app.rag.rerank: enabled/topN/topK/mode`，默认注释关闭）
- `rag/rerank/RerankMetrics.java`（或并入 postprocessor）：`rag.rerank.executions`、`rag.rerank.fallback`、`rag.rerank.candidates`（直方图）

**内容**：
- 计数：执行次数、降级次数（LLM 失败/解析失败）；candidates 长度直方图
- 开关读取走 RerankProperties（@ConfigurationProperties 注入）

## Task 5：单测

**目标**：重排解析/降级/退化逻辑。
**依赖**：Task 1、Task 2
**影响文件**：
- 新增 `rag/rerank/LlmDocumentRerankerTest.java`

**内容**：
- mock ChatModel 返回合法编号 → 保序重排正确
- 返回含非法编号/乱序 → 回退补齐且不丢文档
- 模型抛错 → 降级原顺序前 K
- candidates ≤ topK → 不调模型直接返回
- 文本超长裁剪生效（token 保护）

## Task 6：接入主流程 + 文档同步

**目标**：开关上线 + 文档状态推进。
**依赖**：Task 3-5
**影响文件**：
- `docs/03-技术决策记录.md`（ADR-15 阶段 4 落地补记：rerank 已实现，v1=LLM，接口就绪等 bge-reranker）
- `docs/phase5-rerank/checklist.md`（勾选）
- `docs/02-架构设计.md` / `docs/06-核心流程设计.md`（RAG 链路补 rerank 环节）

## Task 7：端到端验证

**目标**：开关开/关两态行为正确 + 冒烟回归。
**依赖**：Task 6
**影响文件**：
- `scripts/e2e-smoke.sh`（可选加一段 rerank 断言；或人工验证）

**验证内容**：
- 关闭态（默认）：冒烟 16/16 保持（链路与现状一致）
- 开启态：问知识库问题（如"非暴力沟通四要素"）→ 回复含检索内容且比关闭态更全（对比断言或人工）
- 退化：candidates 少时报 warn（日志可见 deg/fallback）
- 指标：开启后 `/actuator/prometheus` 出现 `rag_rerank_*`
- 回归：开/关各跑一轮冒烟无红