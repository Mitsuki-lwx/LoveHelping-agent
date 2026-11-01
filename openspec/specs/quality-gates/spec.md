# 质量门禁 Specification

## Purpose

定义「什么算做完了」的可判定门槛。本规格把历史上散落在 `docs/09`、CI 配置与 pom 里的
门槛固化为需求，并规定**数字必须以实测为准**——写进文档的每一个计数都要能当场复现，
否则不得作为卖点或验收依据。

来源：`docs/09-测试策略.md`、`.github/workflows/ci.yml`、`pom.xml`（2026-11-01 固化）。

## Requirements

### Requirement: 变更完成定义

一次代码修改 SHALL 同时满足：编译通过、相关单测通过、触达面的真实栈 E2E 通过。
仅编译通过 MUST NOT 视为完成。

#### Scenario: 只跑了单测

- **WHEN** 修改了聊天管道但只执行 `./mvnw test`
- **THEN** 不得宣称完成；必须在交付说明里写明「未做 E2E 验证」及待验证项

#### Scenario: 本地依赖不可用

- **WHEN** MySQL/Redis/LLM key 缺失导致 E2E 无法启动
- **THEN** 显式声明未验证并列出待验证项，不得默认通过

### Requirement: 后端单元测试

后端单测 SHALL 全绿，且保持零外部依赖（Mock 掉 LLM/DB/Redis/MCP）。

#### Scenario: 运行单测

- **WHEN** 执行 `./mvnw -B test`
- **THEN** 实测基线 **650 项、0 失败、0 错误、0 跳过**（2026-10-30 实测，耗时约 1m32s）

### Requirement: 覆盖率棘轮

行覆盖率 SHALL 由 JaCoCo 度量并设下限；下限 MUST 钉在已实测的基线上，
MUST NOT 因达不到理想值而下调，也 MUST NOT 写一个从未测过的目标值。

#### Scenario: 覆盖率门禁

- **WHEN** 执行 `./mvnw verify`
- **THEN** `LINE COVEREDRATIO` ≥ **0.38**（`pom.xml` 棘轮），实测 **0.547**（2026-10-30）

#### Scenario: 覆盖率下降

- **WHEN** 变更让行覆盖率跌破棘轮
- **THEN** 构建失败；处理方式是补测试或显式评审后上调/下调棘轮并记录理由

### Requirement: 集成测试层

数据库迁移、约束、向量载荷与 MyBatis 映射 SHALL 在真实容器里验证，且 Docker 缺失时
MUST 跳过而非静默通过。

#### Scenario: 容器内验证

- **WHEN** 执行 `./mvnw verify`
- **THEN** Testcontainers 用例如实运行；`@Testcontainers(disabledWithoutDocker = true)` 保证无 Docker 时显示 SKIP

### Requirement: 真实栈 E2E

端到端冒烟 SHALL 通过真实 HTTP/SSE/数据库与真实模型执行，且 CI 与本地 MUST 使用同一套实现。

#### Scenario: 跑冒烟

- **WHEN** 执行 `python scripts/e2e_live.py --base <url>`
- **THEN** 实测 **27/27 通过**（2026-10-31 实测）

#### Scenario: 附加断言

- **WHEN** 执行 `python scripts/e2e_assertions.py --base <url> --log <app.log> --prometheus <url>/actuator/prometheus`
- **THEN** 实测 **30 通过 / 0 失败 / 1 跳过**；跳过项不得计入通过

### Requirement: 契约漂移检查

文档声明的每个端点 SHALL 在代码中存在；缺失即构建失败。

#### Scenario: 声明了不存在的端点

- **WHEN** `scripts/check_api_contract.py` 输出 MISSING > 0
- **THEN** 退出码 1，CI 变红；修文档或修实现，不允许白名单绕过

#### Scenario: 当前基线

- **WHEN** 以 `openspec/specs/api-contract/spec.md` 为源运行
- **THEN** 实测 MISSING=0、声明 34 条、实现 77 条、EXTRA 46 条（2026-11-01 实测）

### Requirement: 静态检查

SpotBugs SHALL 在 `verify` 阶段以 High 为门槛运行，High 缺陷数 MUST 为 0；
豁免项 MUST 在 `spotbugs-exclude.xml` 里逐条登记理由。

#### Scenario: 新增 High 缺陷

- **WHEN** 代码引入一个 High 级 SpotBugs 缺陷且未登记豁免
- **THEN** 构建失败

### Requirement: 密钥扫描

仓库 SHALL 经 gitleaks 全历史扫描，任何形式的明文密钥 MUST NOT 入库；
误报只能走 `.gitleaks.toml` 白名单，MUST NOT 绕过扫描。

#### Scenario: 提交含密钥

- **WHEN** 一次提交包含形似密钥的字符串
- **THEN** security 工作流失败，CI 变红

### Requirement: 数字口径

文档与简历引用的任何工程数字 SHALL 来自当次可复现的实测，并注明时间与口径；
MUST NOT 使用未在本机或 CI 复现过的数字。

#### Scenario: 更新数字

- **WHEN** 要在文档里写「N 项测试」或「覆盖率 X%」
- **THEN** 先跑对应命令取实测值，并记录日期；与历史值不一致时以最新实测为准并同步旧值
