# spec.md — RAG 阶段4+：检索重排（Rerank）

> **问题**：当前 RAG 检索只召回 top-5 子块再替换父块——召回窗口小（5 条），相关知识条目常被挤掉；且返回顺序纯按向量相似度，最相关的不一定排在最前。ADR-15 阶段 4 规划了"rerank 模型（bge-reranker 中文，召回 Top50→重排 Top5）"。
>
> **方案**：检索阶段把召回窗口扩大到 Top50（子块级候选），新增 **Rerank 重排环节**（query × 候选打分 → 取 Top5 进入生成），提高命中率与上下文质量，再按 ADR-15 的 small-to-large 语义替换为父块全文。
>
> 技术决策见 ADR-15（阶段 4 落地记录）；实现遵循"先低成本高收益、可插拔"原则。

---

## 关键实现决策（best judgment，审 spec 可纠偏）

- **可插拔 `DocumentReranker` 接口**（`rag/rerank/`）：`query + 候选列表 → 重排后的前 K`。
- **v1 = LLM 重排**：用主线 LlmGateway 单次调用，让模型从候选里选 Top-5（附理由，按索引返回）。零新依赖、走既有降级链与 token 计量、可灰度可关。
- **真实 bge-reranker 中文模型**（本地 ONNX 或兼容 API）：实现同一 `DocumentReranker` 接口即可替换——**接口就绪后独立部署任务**，不阻塞 v1 上线。
- **挂载点 = Spring AI advisor 的 postretrieval 阶段**（`DocumentPostProcessor` 实现），不改检索器与 advisor 装配——检索器仅把 topK 从 5 扩到配置的 topN。

## 能力清单

| ID | 能力 | 说明 |
|----|------|------|
| CAP-1 | 大召回窗口 | 检索候选从 5 扩到可配置 topN（默认 50），子块级召回 |
| CAP-2 | 可插拔重排器 | `DocumentReranker` 接口：query × 候选 → 前 K；未来 bge-reranker 换实现即生效 |
| CAP-3 | LLM 重排 v1 | LlmGateway 单次调用输出 Top-5 候选索引（含简短理由）；改动用主模型，享降级/计量/诚实报错 |
| CAP-4 | 重排后小到大 | 重排后的子块仍替换为父块全文（保持 ADR-15 语义，上下文完整） |
| CAP-5 | 开关与观测 | `app.rag.rerank.*`（enabled/topN/topK/mode=llm|off）；`rag.rerank.triggered/attempted` 计数、耗时直方图 |
| CAP-6 | 质量回归意识 | 重排前后对比口径预留（Recall@K，ADR-15 阶段 3 评估集落地时纳入） |

## 设计骨架

```
用户查询
   → 检索器（父块索引，topK=topN=50 子块）
   → [Rerank 重排：LlmGateway 打分 → Top5]   ← DocumentReranker（postretrieval 阶段）
   → 子块 → 父块全文替换（small-to-large）
   → 检索增强注入 → LLM 生成
```

- **关闭时 == 现状**：`app.rag.rerank.enabled=false` 时链路与现线上完全一致（v1 只加在 advisor 后处理阶段，非破坏性）。
- **成本**：每次 RAG 对话多一次 LLM 调用（查询改写已有一轮，本轮再一轮）→ 由 `mode=llm` + 开关控制，关闭可回退。
- **风控**：重排输出必须是候选列表内的索引；非法/缺失索引回退到原向量序（不丢文档）。

## 目标用户

所有走 RAG 的聊天（普通/沙盘）。用户无感知（延迟略增）；后端通过开关灰度。

## Out of Scope（本次）

- **本地 bge-reranker ONNX / API 部署**：模型签发与推理服务化是独立部署任务（接口就绪后接入，另有 ADR/任务）。
- rerank 微调 / 蒸馏。
- ADR-15 阶段 4 其余两项：增量更新（content_hash）与索引蓝绿——本次只做 rerank。