# phase9 · query-rewrite：把"改写好还是坏"变成可测，并修提示词

> **起因**：Mitsuki 问「rewrite 会不会是提示词不够好」。
> 而当时的处境是：`app.rag.query-rewrite.enabled` 默认关，理由是 2026-09-04 评测"改写劣化严重"。
> 但那个结论**没法复现也量不准** —— 改写挂在 `RetrievalAugmentationAdvisor` 的 pre-retrieval 里，
> 而评测用的 `/admin/rag/retrieve` 端点**绕过 advisor、直接调 retriever**，
> 所以"改写的影响"从来没进过评测口径，只能靠聊天链路的日志嗅探。

## 目标

1. **让改写可测**：给 admin 检索端点加 `rewrite` 参数（对齐已有的 `rerank` 参数），
   复用产线 `QueryRewriter`，使同一套 ground truth 能把"不改写 / 各提示词版本"并排跑出来。
2. **回答那个问题**：读提示词原文，定位失效机理，给出**约束版提示词**，再用 45 例对照实测。
3. 旧版提示词**不删**，作为对照组保留可切换。

## 边界

**做**：端点加参数 + 回显（用了哪个 query、哪版提示词）；两版提示词 + 版本开关；单测；对照评测。

**不做**：

- **不改 `enabled` 默认值**（提示词修好了 ≠ 该开。开不开看评测结果，且要用户拍板）。
- 不动 `application.yml`（另一个会话正在改该文件，且它的 `query-rewrite` 块正被改；
  版本开关先用 JVM 参数 `-Dapp.rag.query-rewrite.prompt-version=v1|v2`，yml 占位符待合并后再补）。
- 不碰 embedding/rerank 迁移那条线。

## 拆分

| # | 任务 | 产出 |
| --- | --- | --- |
| T1 | 端点加 `rewrite` 参数 + 回显 effectiveQuery/promptVersion | `AdminController` + 单测 |
| T2 | 两版提示词 + 版本开关（默认 v2） | `QueryRewriter` |
| T3 | 评测脚本支持 `--rewrite` | `scripts/retrieval_eval.py` |
| T4 | 45 例三方对照（不改写 / v1 / v2） | `outputs/rw-*.txt` |
| T5 | 结论文档 + 提交 | 本目录 |

## 风险

- **R1**：LLM 不一定严格遵守约束版提示词（实测已看到个别规范化替换）→ 结论要按**实测指标**下，
  不按"提示词看起来更严"下。
- **R2**：45 例的 ground truth 是既有资产，用它对照是同一口径；但它本身不覆盖改写特有的失败模式。
