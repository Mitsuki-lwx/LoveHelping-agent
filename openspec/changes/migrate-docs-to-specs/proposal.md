# 文档体系统一到 OpenSpec

## Why

仓库里现在有两套并行的事实源：`docs/`（15 篇 + 45 个 phase 目录，共 17,687 行）与新建的 `openspec/`。
历史上每个 phase 各自写 `spec.md / tasks.md / checklist.md`，既不能被机械校验，也回答不了
「系统**现在**必须满足什么」——只能靠倒着读 45 个目录。继续双轨，"文档即事实源"会演变成
"哪份文档算数"的争论。

## What Changes

- **BREAKING（流程）**：所有新需求与变更 SHALL 走 `openspec/changes/<change>/`；
  无对应 change 的代码改动视为流程违规。
- 系统"必须怎样"的唯一事实源收敛到 `openspec/specs/<capability>/spec.md`。
- 45 个 `docs/phase*/` 的 `spec/tasks/checklist` 迁入 `openspec/changes/archive/<phase>/`，
  保留原始内容与日期，只做目录与 frontmatter 适配。
- `docs/01`(产品) / `docs/05`(API) / `docs/07`(安全) / `docs/09`(质量) 中**可执行**的约束
  抽取为 capability specs：`product-scope`、`api-contract`、`safety-guardrails`、`quality-gates`。
- `docs/03-技术决策记录.md`（ADR-1…93）冻结为只读决策归档；**不再新增 ADR**，
  新决策写进对应 change 的 `design.md`，长期有效的约束随 archive 抽进 `specs/`。
- `scripts/check_api_contract.py` 的文档源从 `docs/05-API契约设计.md` 切到
  `openspec/specs/api-contract/spec.md`，使漂移检查跟着新事实源走。

## Capabilities

本次是流程与文档迁移，**不改变系统行为**，故 `skip_specs: true`。
后续每个抽取动作各自作为独立 change，携带自己的 delta 与场景。

## Impact

- 文档：`docs/`（冻结）、`openspec/`
- 工具：`scripts/check_api_contract.py`（文档源路径）、`AGENTS.md`（守则 §1）
- CI：可选新增 `openspec validate --strict` 门禁
- 运行时：**不影响**任何代码、接口、数据库
