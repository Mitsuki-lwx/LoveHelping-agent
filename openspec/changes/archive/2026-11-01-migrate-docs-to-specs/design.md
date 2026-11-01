# 设计：怎么从双轨收敛到单轨

## 目标形态

```
openspec/
  specs/<capability>/spec.md      ← 系统"必须怎样"的唯一事实源
  changes/<name>/                 ← 在途变更（proposal → design → tasks → 实施 → archive）
  changes/archive/<phase>/        ← 历史变更，可追溯
docs/                             ← 冻结只读（历史记录）
docs/03-技术决策记录.md            ← ADR 归档（只读，不再增长）
```

## 映射表

| 现有位置 | 去向 | 处理方式 |
| --- | --- | --- |
| `docs/phase*/spec.md`（40 个） | `openspec/changes/archive/<phase>/proposal.md` | 原样迁入，头部补 `## Why / ## What Changes` 小标题 |
| `docs/phase*/tasks.md`（44 个） | `openspec/changes/archive/<phase>/tasks.md` | 原样迁入（含已完成勾选状态） |
| `docs/phase*/checklist.md`（44 个） | `openspec/changes/archive/<phase>/verification.md` | 改名，避免与 OpenSpec 的 artifact 命名冲突 |
| `docs/01-产品定位与需求.md` | `specs/product-scope/spec.md` | 抽"必须做什么/不做什么"，SRS 追溯号保留 |
| `docs/05-API契约设计.md` | `specs/api-contract/spec.md` | 端点表转为 Requirement + Scenario；脚本改读它 |
| `docs/07-安全与合规.md` | `specs/safety-guardrails/spec.md` | 只抽可判定条款（越权必须 403、L3 必须阻断） |
| `docs/09-测试策略.md` | `specs/quality-gates/spec.md` | 门槛（覆盖棘轮、E2E 27 项、SpotBugs High=0） |
| `docs/02/04/06/08/10/11` | 保持 `docs/` | 属于"当前实现说明"，不是行为约束；待各自 change 逐步抽取 |
| `docs/03`（ADR 1–93） | 保持 `docs/`，冻结 | 只读；新决策进 `design.md` |

## 迁移顺序（每步都可独立发布）

1. **规则先行**：改 `AGENTS.md §1` + `docs/00` 顶栏横幅 → 新工作立刻单轨。
2. **历史归位**：phase 文档批量搬进 `changes/archive/`（纯移动，内容不变）。
3. **契约抽取**：先做 `api-contract`（因为 CI 脚本依赖它，收益最直接）。
4. **其余能力**：`product-scope` → `safety-guardrails` → `quality-gates`，按需随下一次相关变更抽取。

## 风险与对策

| 风险 | 对策 |
| --- | --- |
| 移动 `docs/phase*` 打断 `docs/03` 里的相对链接 | 搬完后跑一次链接检查，断链改成 `openspec/changes/archive/...` 新路径 |
| `check_api_contract.py` 断供导致 CI 假绿 | 先加 `--doc` 双读（旧路径不存在时读新路径），再删旧默认值 |
| 一次性搬 17,687 行导致 review 不可读 | 按上表分 4 步，每步一个 commit；内容不改写只改位置 |
| spec 写成"实现说明"而非"行为要求" | 抽取时只保留带 MUST/SHALL 的可判定条款，实现细节留在 docs/ |

## 回滚

全部改动是文件移动与新增，`git revert` 单个 commit 即可回到双轨状态；运行时无影响。
