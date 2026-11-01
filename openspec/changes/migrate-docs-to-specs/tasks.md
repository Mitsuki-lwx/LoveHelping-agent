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

- [ ] 3.1 `openspec/specs/api-contract/spec.md`：按 capability 写 Requirement + Scenario，
      覆盖 `docs/05` 里现有的全部端点（对照 `scripts/check_api_contract.py` 的解析规则）
- [ ] 3.2 `check_api_contract.py` 增加 `--doc` 双读回退（新路径优先，旧路径兜底）
- [ ] 3.3 切换默认值为 `openspec/specs/api-contract/spec.md`，跑一次 CI 确认 MISSING=0
- [ ] 3.4 `docs/05` 顶部加一行"已被 specs/api-contract 取代，仅存档"

## 4. 其余能力抽取（各自独立 change）

- [ ] 4.1 `specs/product-scope/spec.md` ← `docs/01` + `docs/11§SRS`（P0/P1/不做）
- [ ] 4.2 `specs/safety-guardrails/spec.md` ← `docs/07`（越权 403、L3 阻断、PII 不落库）
- [ ] 4.3 `specs/quality-gates/spec.md` ← `docs/09`（覆盖棘轮、27 项 E2E、SpotBugs High=0）
- [ ] 4.4 每抽取一个，就用 `openspec validate --strict` 与一次真实 E2E 验证没有把行为写歪

## 5. 收尾

- [ ] 5.1 CI 增加 `openspec validate --strict` 步骤（node 已具备，`npx @fission-ai/openspec@1.16.0`）
- [ ] 5.2 `AGENTS.md §2` 的 E2E 清单改指向 `specs/quality-gates`
- [ ] 5.3 归档本 change，`openspec/specs/` 成为唯一事实源
