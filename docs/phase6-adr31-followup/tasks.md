# tasks — ADR-31 收尾：准入完备性（发现二 / 发现三）

**立项**：2026-09-17
**前置阅读**：ADR-23（唯一重试归属者 / 单一准入点）、ADR-29（闸门口径）、ADR-31（准入完备性与容量余量，发现二·三）、ADR-32（AIMD 自适应闸门）、ADR-33（日志降噪）

---

## 1. 目标

把 ADR-31 剩余的两条发现做掉，使"**所有模型调用都经过同一个准入点，且准入凭证覆盖整条重试/降级链路**"这一不变式真正成立。

| 编号 | 发现 | 一句话 | 判定 |
| --- | --- | --- | --- |
| 发现二 | 重试/降级不持有并发 permit | `streamAttempt` 在 `doOnError` 里先 `release()` 再让 `onErrorResume` 订阅重试/降级 → 两次尝试之间出现**准入空窗**，厂商侧瞬时在途可突破 `max-concurrent-calls` | 本次修复 |
| 发现三 | 两处旁路调用绕过网关 | `EvolutionConfig:107`（`SkillReflector`）与 `InsightService:38` 用 `@Qualifier("openAiChatModel")` 直接注入裸模型，绕过 permit、熔断、用量归因 | 本次修复 |

## 2. 边界

### 做什么

1. **发现三**：两处注入改为 `@Primary ChatModel`（= `LlmGateway`），使其纳入 permit / 熔断 / 重试预算 / `llm.usage.owner=gateway` 用量归因。
2. **发现三附带**：`SkillReflector` 的兜底 `catch` 当前对任何异常都 `log.error(..., e)` 打全栈。走网关后"自家容量满（4003）"会成为常见且**预期**的结果（后台任务为用户流量让路），必须降级为 WARN 单行，与 ADR-33 的降噪口径一致。
3. **发现二**：把并发 permit 的获取/释放从"每次尝试"上提到"每次用户请求"，使一次请求的重试 + 降级全程持有同一张凭证；同步路径 `call()` 与流式路径 `stream()` 行为对齐。
4. **防回归**：补单测把上述两条不变式钉住。

### 不做什么

- 不改 AIMD 算法（收缩/回升策略）、不改熔断判定、不改闸门数值。
- 不改后台任务的**调度预算**语义（`SchedulerBudget` 保持现状；本次只是让它之后还要再经过一层准入）。
- 不改动 `ChatExecutor` / `MemoryExtractor` / `AgentLlmNode` / `QueryRewriter` / `MyKeywordEnricher`（它们已经走网关）。
- 不引入排队/优先级机制（后台任务被 4003 拒绝即跳过本轮，这是预期行为）。
- 不做多实例容量协调（ADR-23 既定）。

## 3. 拆分

| # | 事项 | 产出 |
| --- | --- | --- |
| T1 | 三件套（本文件 + `spec.md` + `checklist.md`） | docs |
| T2 | 两处注入改为 `@Primary`（走网关） | `EvolutionConfig.java`、`InsightService.java` |
| T3 | `SkillReflector` 容量拒绝降级为 WARN 单行 | `SkillReflector.java` |
| T4 | 同步路径 permit 上提（`call()` 持证覆盖重试 + 降级） | `LlmGateway.java` |
| T5 | 流式路径 permit 上提（`stream()` 持证覆盖重试 + 降级） | `LlmGateway.java` |
| T6 | 单测：permit 跨重试空窗仍被持有（同步 + 流式） | `LlmGatewayTest.java` |
| T7 | 单测：单一准入点防回归（禁止非网关处直连裸模型） | `AdmissionCompletenessTest.java` |
| T8 | 验证：编译 → 全量单测 → 真实 E2E → 运行时观测（insight 走网关） | 证据 |
| T9 | 文档：ADR-31 状态更新 + `docs/09` 实测记录 | docs |
| T10 | 提交 + 推送 + 项目记忆 | git / memory |

## 4. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| permit 持证时间变长（重试期间不再释放）→ 其他请求更容易被拒 | 这是**正确**的：厂商侧在途本来就被这次请求占着，原来的"先释放"是把账记错了。用 E2E + 指标确认无功能回归 |
| `InsightService` 走网关后用户请求可能被 4003 拒 | 这是预期（用户本就该看到"A I 服务繁忙"而不是裸模型无限打）。端点上验证返回码正确 |
| `SkillReflector` 走网关后定时任务可能被容量拒绝 | 降级为 WARN 单行 + 本轮跳过；反思是"下一轮还会再来"的幂等任务 |
| 两处改成 `@Primary` 后 Spring 装配歧义 | `primaryChatModel` 是唯一 `@Primary`；用启动 + E2E 验证装配无误 |

## 5. 完成定义

编译通过、全量单测全绿（≥202 项）、真实 E2E 22/22、ADR-31 状态更新、提交推送、工作区干净。
