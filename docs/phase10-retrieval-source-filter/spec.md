# phase10 · 方案与实测

> 状态：见 `checklist.md`（跑完填）。

## S1 根因

`ParentChildDocumentRetriever.retrieve()` 的执行顺序是：

```
取候选（内部已 .limit(k) 截断）  →  Java 层丢弃 source=memory|evolution  →  返回
        ↑ 记忆块在这里占位                ↑ 丢失的位子不会补人
```

正确顺序应是：**过取 → 过滤 → 截断**。否则"过滤"等于把候选池缩小，
而不是把不需要的东西剔出去。

配套缺陷：`int k = ... && "llm".equals(mode) ? getTopN() : topK;`
把 ADR-25 的"rerank 开启即扩窗"绑死在 `llm` 一种模式上，`remote` 拿不到扩窗。

## S2 改动

```java
// ① 谓词抽出（两处共用，避免漂移）
private static boolean isKnowledgeDoc(Document d) {
    Object src = d.getMetadata().get("source");
    return !"memory".equals(src) && !"evolution".equals(src);
}

// ② 扩窗条件：任一生效模式（enabled && mode != off）
int k = rerankProperties.isActive() ? rerankProperties.getTopN() : topK;

// ③ hybrid 分支：排序 → 过滤 → 截断
List<Document> merged = fused.values().stream()
        .sorted((a, b) -> Double.compare(b.totalScore(), a.totalScore()))
        .map(r -> r.doc)
        .filter(ParentChildDocumentRetriever::isKnowledgeDoc)   // ← 在 limit 之前
        .limit(topK)
        .toList();

// ④ 非 hybrid 分支保持同一形状（过取 ×3 → 过滤 → 截断）
List<Document> children = hybridEnabled
        ? hybridRetrieve(query.text(), k)
        : vectorStore.similaritySearch(SearchRequest.builder().query(query.text()).topK(k * 3).build())
              .stream().filter(ParentChildDocumentRetriever::isKnowledgeDoc).limit(k).toList();
```

保留 `retrieve()` 末尾的那道过滤作为**兜底**（防御未来新增通道），但它不再是唯一防线。

**为什么不直接用 Spring AI 的 `filterExpression`**：依赖其 pgvector 实现对 JSON 元数据的过滤能力，
且知识块的 metadata **里没有 `source` 字段**（`source` 只在 memory/evolution 行上有），
写成 `source != 'memory'` 很可能把知识块一起滤掉。先用"过取 + 过滤 + 截断"：零新依赖、行为可预测。

## S3 预期与代价

- 聊天链路候选从 5 → 8（`app.rag.top-k`），若开启重排则 k=20 → 过滤后仍应有足够知识块
  → **重排第一次真正在聊天链路生效**。
- **代价**：`remote` 模式开启后，向量通道取 `topN*3 = 60` 条（原先 24），
  embedding 端多算但 rerank 端候选仍是 `topN=20`（截断点由 postprocessor 控制）。
  实际额外开销主要在**向量检索的召回条数**，不是外部调用次数。
- ⚠️ 候选变多后，`hits=20` 会进入 postprocessor → 触发一次 rerank 外部调用
  （这是预期行为：宽召回 + 精排）。

## S4 实测结果

见 `checklist.md`（跑完填）。

## S4 实测结果（2026-09-23）

脚本 `scripts/verify_phase10.sh`（同一 app 实例给出两个口径：admin `rerank=false` = 纯检索基线，
`--rerank` = 走 postprocessor）。

| 观测项 | 修复前 | 修复后 |
| --- | --- | --- |
| 聊天链路候选数 | `hits=5` | **`hits=20`** |
| 聊天链路重排调用 | **0 次** | **1 次**（终于生效） |
| 45 例纯检索基线 MRR@5 | 0.751 | 0.756 |
| 45 例 + 远端 8B MRR@5 | 0.856 | 0.860 |
| 单测 | 276/276 | **282/282** |
| 真实 E2E | 22/22 | 22/22 |

**对照实验**（两处失效注入，均精确失败）：

- `filter` 挪回 `limit` 之后 → `expected: <5> but was: <1>`
- 扩窗条件还原成只认 `llm` → `expected: <60> but was: <15>`

### 结论①：结构性修复确凿，指标提升不确凿

候选 5→20、重排 0→1 次调用 —— 这是**结构性**变化，确凿。
但 MRR 的 **+0.005 / +0.004** 落在既有噪声带（0.012）内，
且本轮**只跑了单轮** → **不能当收益**。要下"质量变好"的结论必须多轮。

### 结论②：我的"记忆挤占解释掉分"假设 —— **被实测否定**

原本的推测是：上一轮 MRR 0.807 → 0.751 的下降，主要来自记忆块挤占候选位。
修掉挤占后基线只回到 **0.756（+0.005）**，**没有回到 0.807 量级** → **该解释不成立**。

同时发现这个跨期对比**本身不干净**：扩窗修好后基线窗口变成 `topN=20`（粗召回 60 条），
而 0.807 是当年 `k=8`（粗召回 24 条）下测的 —— **关键词通道长度变了，RRF 融合结果会变**。
因此"换 embedding 是否掉分"**仍未定论**；比之前进步的是：知道了"为什么难比"。

### 结论③：语义通道的压制**没有修**（本期只修了顺序）

向量通道 top-N 的构成（实测）：

```
top-8 : memory=6  knowledge=2
top-24: memory=19 knowledge=5
top-60: memory=49 knowledge=11
```

即使取到 60 条，知识块也只有 11 条 —— 记忆（385 行，且都是本领域对话摘要）
几乎占满了语义最近的位子。本期把"过滤"挪到截断之前，恢复了**候选数量**；
但**知识检索的语义信号仍被压制**，现在主要靠关键词通道供料。

真正修法：把源过滤**下推到 SQL**（`filterExpression` 或自建 ANN 查询），
让向量通道直接返回 `k` 条*知识*候选。**本轮未做**（风险：知识块 metadata 里没有 `source` 字段，
`source != 'memory'` 很可能把知识块一起滤掉，需先验证表达式语义）。

### S5 成本（实测 usage，不再是推算）

单次重排调用的真实计费量（20 条候选 / 7,141 字符，与 `maxCandidateChars=1000` 的上限形状一致）：

```
meta.billed_units.input_tokens = 6219     （约 0.87 token / 中文字符）
```

按 `Qwen3-Reranker-8B` = **¥0.28 / M tokens**：

| 量级 | 成本 |
| --- | --- |
| 单次检索 | **¥0.0017** |
| 1 万次 | ≈ ¥17.4 |
| 10 万次 | ≈ ¥174 |

⚠️ **月成本算不出来**（缺真实日检索量）。spec 旧文里"¥3.55/月"反推约 2,040 次/月 ——
那是**低流量假设**下的数字，不应被当成结论引用。

### S6 未验证

- 并发/延迟：`scripts/verify_rerank_load.sh` 修好前提断言后**尚未重跑**（TTFT 分布、
  `maxConcurrent=2` 是否降级、冷启动 5.21s 是否触发超时，均空着）。
- 多轮评测（本页所有指标都是单轮）。
- 未做"关掉重排再跑一轮"的对照来解释日志里那 1 条上游 `Connection reset`。
