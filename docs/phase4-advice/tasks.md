# tasks.md — 话术三级建议（FR-CORE-01） 实现任务

> **前置**：ADR-18（已立，2026-08-25）。情绪刹车片（FR-CORE-02）已完成，不在本表。
> 每条任务可在一次专注会话内完成，标注影响文件、依赖、参考资料。

---

## ✅ 已完成任务（情绪刹车片，历史）

- ~~Task A：情绪刹车片规则配置（V15）~~ → 已完成
- ~~Task B：情绪刹车片判断逻辑（ChatEntry.emotionBrakeCheck）~~ → 已完成
- ~~Task C：情绪刹车片 E2E 验证~~ → 已完成

---

## Task 1：话术请求触发识别（CapabilityRouter.isAdviceRequest）

**目标**：新增"是否沟通建议请求"的纯规则判断，独立于 `needTools`。
**依赖**：无
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/CapabilityRouter.java`

**内容**：
- 新增 `public boolean isAdviceRequest(String message)`，正则匹配沟通建议意图：
  `怎么回复|怎么回|说什么|该说|怎么哄|怎么道歉|如何开口|怎么开口|回什么|怎么说|怎么拒绝|怎么表白`
- 以 `needTools` 类似写法：`matches("(?i).{0,5}(…).{0,30}")`
- **不改变** `needTools` 语义（话术请求不需要工具，仍走 ChatExecutor）

---

## Task 2：ChatEntry 接入话术判定 + 指标

**目标**：入口识别话术请求并记录触发指标，透传激活标记。
**依赖**：Task 1
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/ChatEntry.java`
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/AgentResult.java`（如需要透传标记）

**内容**：
- `chat()` 内：`boolean advice = router.isAdviceRequest(message)`
- 无工具路径改为 `chatExecutor.execute(message, chatId, caps, null, advice)`
- 建议请求且走普通路径：`meterRegistry.counter("advice.request").increment()`
- 仅当 advice 且后续确实触发三牌才计 `advice.activated`（见 Task 3 输出侧）

---

## Task 3：ChatExecutor 结构化 advice 输出 + 激活段

**目标**：话术请求时激活结构、输出侧产出结构化 advice 事件（增量、向后兼容）。
**依赖**：Task 2
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/ChatExecutor.java`

**内容**：
- 新增 `execute(message, chatId, caps, customSystemPrompt, boolean advice)` 重载（保留旧的委托）
- 复用既有"画像/状态/记忆上下文"注入；advice=true 时向 system prompt 附加激活段：
  强调"严格按三牌格式输出、每牌必须附对方可能反应、不得输出操控性内容"
- **触发判定**：完整流结束（`collectList().block()`）后按三牌标记（🛡️/⚡/🌸）切片出 tiers
  - 切出 ≥ 2 牌 → 计 `advice.activated`，产出结构化数据
  - 切不出 → 降级为纯文本（前端容错），不报错
- 事件格式与 SSE 桥接对接方式以 `AgentResult` 现有返回值扩展为准，保持纯文本兼容

---

## Task 4：SSE 桥接 advice 事件（增量）

**目标**：流中新增 `{"type":"advice",...}` 事件类型，非话术回复不变。
**依赖**：Task 3 输出的结构化数据
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/controller/ChatController.java` 或 ChatService 的 SSE 序列化处

**内容**：
- 识别到结构化 tiers 时，发送 `event: advice` + 对应 JSON（与现 event/data 约定一致）
- 纯文本流转发方式不变

---

## Task 5：单测 + 文档同步

**目标**：关键逻辑单测 + 文档状态推进。
**依赖**：Task 1-4
**影响文件**：
- `src/test/java/cn/lwx/lwxaiagent/.../CapabilityRouterTest.java`（新增）
- `docs/phase4-advice/checklist.md`（勾选）
- `docs/10-重构路线图.md`（FR-CORE-01 立项项 → 已实现）
- `docs/11-软件需求规格说明书.md` §3.2 状态更新

**内容**：
- `isAdviceRequest` 正/负例单测（命中"怎么回复/怎么道"，不命中"今天天气"）
- 三牌切片逻辑单测（有 3 牌 / 无牌降级）

---

## Task 6：E2E 验证

**目标**：话术三级 + 优先级 + 护栏冲突全流程冒烟。
**依赖**：Task 1-5
**影响文件**：
- `scripts/e2e-smoke.sh`（新增 7.10 话术三级段，或复用 7.9）

**验证内容**：
- 用户问"她三天没回消息我怎么回复" → 输出含安全牌/进击牌/后撤牌
- 问"怎么哄她开心" → 三牌 + 对方可能反应
- 问"怎么PUA她" → L3 阻断（不是三牌）
- 问"今天天气" → 纯文本，无三牌（无误伤）
- 深夜极端词 + 话术请求 → 情绪刹车片优先（冷静提示，非三牌）
