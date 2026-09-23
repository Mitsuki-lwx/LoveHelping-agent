# phase10 · 检索源过滤要在**截断之前**发生（否则记忆块白占候选位）

> **起因**：量「rerank 默认开启」的并发代价时，前提断言（`scripts/verify_rerank_load.sh` 第 ① 步）
> 直接拦住了我 —— 聊天链路 `RAG_RETRIEVAL ... hits=5`，而 `Rerank call` **0 次**。
> 追下去发现：不是重排坏了，是**知识检索的候选被用户记忆挤没了**。

## 现象（实测数据）

对 `我们冷战了，该怎么开口沟通？` 直接查向量库 top-24：

```
 1. memory  cos=0.7778      4. (knowledge) cos=0.7114
 2. memory  cos=0.7463      5. (knowledge) cos=0.6461
 3. memory  cos=0.7202      6. memory  cos=0.6391
 ...                       ...
      top-24 里 memory = 20 条，knowledge = 4 条
      top-8  里 memory =  6 条 → 过滤后知识块只剩 2 条
```

聊天链路日志：`vector=24 keyword=24 fused=8` → 过滤后 `hits=5`
→ 后处理器 `documents.size() <= topK(5)` → **重排被静默跳过**（一次没调用）。

## 两个缺陷

**① 过滤时机错**：`retrieve()` 先取 top-k（`hybridRetrieve` 里 `.limit(topK)`），
**再**在 Java 层过滤 `source=memory|evolution`。被过滤掉的位子**不会补人**
→ 知识块被记忆块挤掉，且调用方看不出来。

**② 扩窗条件写窄了**：`int k = rerankProperties.isEnabled() && "llm".equals(mode) ? getTopN() : topK;`
—— 只有 `mode=llm` 才扩窗到 `topN(=20)`。而 ADR-25 的设计意图是"**rerank 开启时** topK 扩为粗召回窗口 topN"。
`remote` 拿不到扩窗 → 候选池小 → 更依赖"过滤后还能剩下几个"。

## 为什么现在才暴露

**用户记忆原先在另一个向量空间**（旧厂商向量），与查询近似正交 → 永远排不进 top-k
→ 过滤是"空操作"，缺陷被掩盖。2026-09-21 把记忆行也重嵌入到新空间后（**这步本身是必需的**，
见 ADR-39 决策 2），记忆开始**真实竞争候选位**，缺陷立刻生效。

→ 换句话说：**我上一轮的重嵌入让这个潜伏缺陷转成了活跃缺陷**，
且它同时污染了我对 embedding 迁移效果（0.807 → 0.751）的归因 —— 那个下降很可能主要来自这里，
而不是换了 embedding 模型。

## 目标

1. 让"源过滤"发生在**截断之前**（候选池按过滤后的量算），四个消费点统一。
2. 让扩窗条件覆盖所有**生效中的** rerank 模式（不只是 `llm`）。
3. 用**单测**把"过滤先于截断"这条顺序钉死（这是本次真正被漏掉的东西）。
4. 复测：修复后基线 MRR 是否回到 0.807 量级 → 用于判定"换 embedding 到底有没有掉分"。

## 边界

**做**：`ParentChildDocumentRetriever` 的候选获取与过滤顺序；扩窗条件；单测；真实复测。

**不做**：
- 不改 `MemoryVectorStore` / `SkillRetriever` / `KnowledgeSearchTool`（同类"先取后滤"，
  但**本轮的证伪对象是知识检索**；它们各自另立项，见 tasks 的"后续"）。
- 不改 embedding / rerank 的默认值。
- 不引入 Spring AI 的 `filterExpression`（依赖其 pgvector 实现的元数据过滤支持，
  本轮无把握；先用"过取 + 过滤 + 截断"，零新依赖、行为可预测）。

## 拆分

| # | 任务 | 产出 |
| --- | --- | --- |
| T1 | 抽 `isKnowledgeDoc()` 谓词，统一两处过滤 | `ParentChildDocumentRetriever` |
| T2 | hybrid 分支：排序 → **过滤** → 截断 | 同上 |
| T3 | 非 hybrid 分支：过取 → 过滤 → 截断（保持一致形状） | 同上 |
| T4 | 扩窗条件改为"任一生效模式" | 同上 |
| T5 | 单测：过滤先于截断（对照实验可验） | `ParentChildDocumentRetrieverTest` |
| T6 | 真实复测：聊天 hits、重排是否生效、基线/重排 MRR | `outputs/` |

## 后续（本轮不做，已记档）

- `MemoryVectorStore.searchMemory()`：`topK*3` 全库取再按 `userId` 过滤 —— 同族缺陷
  （记忆被知识块挤掉）。修复前它"能用"是因为 `topK*3=15` 而 top-15 恰好都是记忆（实测），
  但这是**运气**，不是设计。
- `SkillRetriever.search()`：`topK` 取再按 `source=evolution` 过滤。
- `KnowledgeSearchTool` 的兜底分支：`PgVectorVectorStore.similaritySearch` 无任何源过滤。
