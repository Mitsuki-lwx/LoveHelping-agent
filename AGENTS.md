# AGENTS.md — 本仓库的 Agent 工作守则

任何 AI 助手（或人）在本仓库工作前必须先读本文件。

## 1. 唯一工作流：OpenSpec

需求、变更、规格**只走 OpenSpec**（`openspec/`，CLI ≥ 1.16）。

- **新需求** → `openspec/changes/<change>/`：`proposal.md`（做什么/为什么）、`design.md`（怎么定与代价）、`tasks.md`（步骤）、`specs/` delta。技能：`openspec-propose`
- **实施** → 按 `tasks.md` 逐项做。技能：`openspec-apply-change`
- **完成** → `openspec archive`，delta 合入 `openspec/specs/<capability>/spec.md`
- **系统「必须怎样」的唯一事实源 = `openspec/specs/`**。改代码前先确认有对应 change；没有就先 propose。
- 跨变更的长期决策：写进对应 change 的 `design.md`；归档时把仍然生效的约束抽进 `specs/`。

### 历史文档（只读，不再更新）

| 位置 | 内容 | 状态 |
| --- | --- | --- |
| `docs/00`–`11` | OpenSpec 之前的需求/架构/契约/测试记录 | 冻结 |
| `docs/phase*`（45 个） | 历史变更的 spec/tasks/checklist | 冻结（迁入 `openspec/changes/archive/`，见 change `migrate-docs-to-specs`） |
| `docs/03-技术决策记录.md` | ADR-1…93 | 冻结：**不再新增 ADR**；新决策进 `design.md` |
| `archive/` | 更早的历史资料 | 不参考 |

例外（机器依赖，迁移完成前保持可读）：`scripts/check_api_contract.py` 仍读 `docs/05-API契约设计.md`；迁移后改指 `openspec/specs/api-contract/spec.md`。

## 2. 每次修改必须端到端验证（硬性纪律）

**Definition of Done = 编译通过 + 相关单测通过 + 触达面的 E2E 冒烟通过。** 三者缺一不可，只有编译通过不算完成。

- E2E 冒烟最小路径与命令见 `docs/09-测试策略.md` §7（登录 → SSE 聊天 → RAG → Agent → 记忆 → 安全负向用例）。
- 改动触达哪个面，就跑哪一段冒烟：动认证/拦截器跑 §7.1+§7.6；动聊天管道跑 §7.2–7.4；动 Agent/工具跑 §7.5。
- 本地依赖（MySQL/Redis/API Key）不可用时：明确声明"未做 E2E 验证"并列出待验证项，不得默认通过。

## 3. 安全底线

- 密钥/密码一律环境变量注入，任何形式的明文密钥（含测试代码、示例配置）都不提交。
- 每次提交前自查无新增越权面（跨用户资源访问必须校验归属，见 docs/07 §3）。
- CI 有 gitleaks 全历史扫描，误报走 `.gitleaks.toml` 白名单，不允许绕过。

## 4. 工程习惯

- DDL 只经 Flyway（`src/main/resources/db/migration`），禁止改 schema.sql。
- 遵循 ADR-13：多租户完备性工程冻结，tenant_id 固定 `default`，不新增租户维度代码。
- 重构期间新旧并存不超过一个 Phase；删除旧代码与迁移新代码同一 PR。
