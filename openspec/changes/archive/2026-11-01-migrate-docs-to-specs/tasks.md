# 任务

## 1. 规则先行（本 change 内完成）

- [x] 1.1 `AGENTS.md §1` 改为「唯一工作流：OpenSpec」，并列出历史文档的冻结状态
- [x] 1.2 `docs/00-文档导读.md` 顶部加冻结横幅，指向 `openspec/`
- [x] 1.3 建出本 change，作为后续迁移的容器（`skip_specs: true`：不改系统行为）

## 2. 历史归位

- [x] 2.1 写一次性脚本 `scripts/migrate_phase_docs.py`：把 `docs/phase*/` 复制为
      `openspec/changes/archive/<phase>/`（`spec.md→proposal.md`、`checklist.md→verification.md`，
      `tasks.md` 原名），头部补一行"迁移自 docs/…，原文未改"
- [x] 2.2 试跑 1 个 phase（`phase4-advice`），人工核对产物
- [x] 2.3 全量执行 45 个 phase：130 个文件入库（127 新写 + 3 已存在跳过）
- [x] 2.4 修 `docs/03`/`docs/09`/`knowledge-base-sources.md`/14 个脚本里指向 `docs/phase*/` 的引用（共 74 处）
- [x] 2.5 删除 `docs/phase*/`（与 2.3 同一提交，遵循"新旧并存不超过一个 Phase"）

## 3. API 契约抽取（CI 依赖，优先）

- [x] 3.1 `openspec/specs/api-contract/spec.md`：12 个 Requirement / 34 条端点声明，覆盖 `docs/05` 全部端点并补上分析层与同意项
- [x] 3.2 `check_api_contract.py` 改为「新路径优先、旧文档兜底」，并更新 docstring
- [x] 3.3 实测：新 spec `MISSING=0`（声明 34 / 实现 77 / EXTRA 46，旧文档是 24/77/56）；默认无参调用已读新路径
- [x] 3.4 `docs/05` 顶部加"已迁到 specs/api-contract，仅存档"横幅

## 4. 其余能力抽取（各自独立 change）

- [x] 4.1 `specs/product-scope/spec.md` ← `docs/01` + SRS（P0 / 明确不做 / 数据驱动立项）
- [x] 4.2 `specs/safety-guardrails/spec.md` ← `docs/07`（密钥、越权 403、L3 分级、出域、未成年人、XSS）
- [x] 4.3 `specs/quality-gates/spec.md` ← `docs/09`（650 单测、覆盖棘轮、27 项 E2E、SpotBugs、gitleaks）
- [x] 4.4 四个 spec 全部 `openspec validate --strict` 通过（4/4）

## 5. 收尾

- [x] 5.1 CI 增加 `openspec validate --strict` 步骤（钉版本 `@fission-ai/openspec@1.16.0`）
- [x] 5.2 `AGENTS.md §2` 的 E2E 门槛改指 `specs/quality-gates`
- [x] 5.3 归档本 change，`openspec/specs/` 成为唯一事实源
