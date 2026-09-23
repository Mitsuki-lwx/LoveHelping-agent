# phase10 · 验收清单（已填实测）

> 复测脚本：`scripts/verify_phase10.sh`；日志 `logs/app-phase10-103116.log`；产物 `outputs/p10-*.txt`。

## A. 顺序与语义

- [x] A1 抽出 `isKnowledgeDoc()` 谓词，`retrieve()` 与 `hybridRetrieve()` **共用同一份**
- [x] A2 hybrid 分支：排序 → **过滤** → 截断（`filter` 在 `limit` 之前）
- [x] A3 非 hybrid 分支：过取 `topK*3` → 过滤 → 截断
- [x] A4 `retrieve()` 末尾保留兜底过滤（防御未来新增通道），并注明"不能是唯一防线"
- [x] A5 扩窗条件改为 `rerankProperties.isActive()`；`mode=remote` 现在能扩窗到 `topN`
- [x] A6 `mode=off` 时 `k` 仍等于 `topK`（不扩窗）

## B. 单测

- [x] B1 `ParentChildDocumentRetrieverTest` 6 例（新增），**修复后全绿**
- [x] B2 **对照实验**：
      - 注入 A（`filter` 挪回 `limit` 之后）→ `memoryBlocks_doNotConsumeTopKSlots_hybrid`
        **精确失败**：`expected: <5> but was: <1>`
      - 注入 B（扩窗条件还原成只认 `llm`）→ `rerankActive_remoteMode_alsoWidensWindowToTopN`
        **精确失败**：`expected: <60> but was: <15>`
      - 恢复后与备份**逐字节一致**，全量 **282/282**（基线 276）
- [x] B3 覆盖非 hybrid 兜底分支（断言过取 ×3）
- [x] B4 `mode=off` 不扩窗（单测断言 `topK=15`）

## C. 真实链路复测

- [x] C1 聊天候选恢复：`RAG_RETRIEVAL ... hits=5` → **`hits=20`**（扩窗生效后 k=topN=20）
- [x] C2 **重排终于在聊天链路生效**：`Rerank call` 次数 **0 → 1**（修复前恒为 0）
- [x] C3 45 例基线（admin `rerank=false`，不走 postprocessor）：
      Recall 0.96 / **MRR 0.751 → 0.756**（修复前 0.751）
- [x] C4 45 例 + 远端 8B：Recall 0.96 / **MRR 0.856 → 0.860** —— **不低于**修复前
- [x] C5 向量通道构成（实测，**结论是"仍未解决"**）：
      `top-8: memory=6/knowledge=2`、`top-24: 19/5`、**`top-60: 49/11`**
      → 记忆仍主导向量通道；本期只修了"过滤不得吃掉候选位"，
      **语义通道被记忆压制这一层没修**（见 tasks.md「后续」）
- [x] C6 真实 E2E **22/22**

## D. 交付

- [x] D1 编译 + 全量单测 **282/282**
- [x] D2 真实 E2E 22/22
- [x] D3 产品默认值未变（rerank 仍 `enabled=true/mode=remote`；embedding 仍 `siliconflow`）
- [x] D4 只提交本轮自己的文件
- [x] D5 ADR-40 / `docs/09 §8.22` / 记忆已同步
- [x] D6 未验证项见下

## E. 结论口径（**关键，如实**）

- [x] E1/E2 **判定结果：E2 —— 我的假设不成立。**
      我原以为"上一轮 0.751 是记忆挤占造成的"。修复挤占后基线只从 **0.751 → 0.756（+0.005，噪声内）**，
      **没有回到 0.807 量级** → **「记忆挤占解释 0.807→0.751 的下降」被实测否定**。
      同时发现这个对比本身**不干净**：修好扩窗后基线用的窗口是 `topN=20`（粗召回 60），
      而 0.807 是当年 `k=8`（粗召回 24）下测的，**关键词通道长度变了 → RRF 融合结果会变**。
      所以只能说：**"换 embedding 是否掉分"仍未定论**，且比之前更清楚"为什么难比"。

## 未验证 / 局限（报忧不报喜）

- ❌ **向量通道的语义信号仍被记忆压制**（top-60 里 49 条是记忆）。本期修的是"过滤不吃候选位"，
      **没有**让知识检索拿到 60 条 *知识* 候选。真正修法是把源过滤下推到 SQL
      （Spring AI 的 `filterExpression`，或自建带过滤的 ANN 查询）—— **未做，见 tasks.md「后续」**。
      ⚠️ 注意：知识块 metadata **没有 `source` 字段**，写 `source != 'memory'` 很可能把知识块一起滤掉，
      必须先验证过滤表达式语义。
- ❌ **未做多轮**：C3/C4 都是**单轮** 45 例。按既有噪声纪律（同一配置两轮可差 0.012），
      0.751→0.756 与 0.856→0.860 这两个 +0.005/+0.004 的位移**都在噪声内，不能当收益**。
      本期确凿的是**结构性变化**（候选 5→20、重排 0→1 次调用），不是指标提升。
- ❌ **成本只算了单价**：实测 20 条候选 / 7,141 字符 → **6,219 input tokens** → `¥0.28/M` ⇒
      **¥0.0017 / 次检索**（1 万次 ≈ ¥17.4、10 万次 ≈ ¥174）。但**月成本算不出来**——
      缺真实日检索量。spec 里旧的"¥3.55/月"反推约 2,040 次/月，说明那是**低流量假设**，不应直接引用。
- ❌ **并发/延迟仍未测**：`scripts/verify_rerank_load.sh` 的前提断言在本期第一次就把自己拦住了
      （prompt 没触发重排）；修好之后**还没重跑**。TTFT 分布、`maxConcurrent=2` 是否降级、
      冷启动 5.21s 是否触发超时，**都还空着**。
- ⚠️ 本轮日志里有 **1 条应用层 ERROR**：`MessageAggregator: Aggregation Error` /
      `WebClientRequestException: Connection reset`（LLM 流上的上游瞬时断连）。
      同一时刻重排调用**成功**，E2E 仍 22/22 → **未观察到用户可见影响**；
      但**没有**做"关掉重排再跑一轮"的对照，不能断言它与本次改动无关。
