# tasks.md — 话术三级建议 + 情绪刹车片 实现任务

> **前置条件**：无（两个功能均无 ADR 前置，直接实现）

---

## Task 1：情绪刹车片规则配置（DB）

**目标**：在 `guardrail_rule` 表新增情绪刹车片专用规则，含深夜时段逻辑  
**依赖**：无  
**影响文件**：
- `src/main/resources/db/migration/V15__emotion_brake_rules.sql`

**内容**：
- 插入 3-5 条极端情绪词规则（`rule_id = emotion_brake_*`），`level=2`（L2 降温类）
- 规则范围：分手/你从来/滚/受不了/想死/完了/废物/去死/恨 等
- 注意：这些是**用户输出**拦截词（区别于护栏的 L3 自伤/PUA 等 L3 阻断词）

---

## Task 2：情绪刹车片判断逻辑

**目标**：在 ChatEntry 入口实现"深夜+极端情绪词 → 冷静提示"逻辑  
**依赖**：Task 1  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/ChatEntry.java`（新增 `emotionBrakeCheck` 方法）

**逻辑**：
```
if (当前时间 ∈ 23:00-06:00) AND (消息命中极端情绪词):
    → 返回冷静提示文案（不阻断，提供继续发送选项）
    → 记录 guardrail_event（level=2，rule_id=emotion_brake_夜）
    → 不进入 ChatExecutor/AgentExecutor
else:
    → 正常继续
```

**冷静提示文案设计**：
- 不评价用户情绪（"我知道你现在很难受"）
- 给冷静期建议（"深夜情绪容易放大，可以先睡一觉再发"）
- 提供转换句式选项（"如果你想换个更温和的说法，我可以帮你"）
- 用户可以"继续发送"（不阻断，信任红线）

**配置**：
- `app.emotion-brake.enabled: true`（application.yml）
- `app.emotion-brake.start-hour: 23` / `end-hour: 6`（可配置时段）
- 极端词清单从 `guardrail_rule` 表读取（不硬编码）

---

## Task 3：话术三级系统提示词增强

**目标**：在 `ChatExecutor.SYSTEM_PROMPT` 中强化话术三级生成指令  
**依赖**：无  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/ChatExecutor.java`（SYSTEM_PROMPT 段）

**内容**（已初步实现，需完善）：
- 当用户问"怎么回复/说什么/如何开口"等沟通建议时，强制输出三种牌
- 每种牌格式：`🛡️ 安全牌（保守）: ...` / `⚡ 进击牌（主动）: ...` / `🌸 后撤牌（给空间）: ...`
- 每种牌附"对方可能反应"（只描述可能性，不保证）
- **不包含**操控/PUA 类暗示（L3 护栏兜底）

---

## Task 4：话术结构化输出 + 前端适配

**目标**：话术三级在 SSE 流中以结构化格式输出（便于前端渲染）  
**依赖**：Task 3  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/ai/AgentLoopExecutor.java`（SSE 事件格式）

**内容**：
- 当 AI 回复包含话术三级时，SSE 事件格式从纯文本升级为结构化 JSON：
  ```json
  {"type":"advice","tiers":[{"name":"安全牌","content":"...","reaction":"..."},...]}
  ```
- 非话术回复保持纯文本格式不变
- **待讨论**：AI 是否需要知道"现在是话术请求"？如果是，需要在 system prompt 中注入标记；如果不是，靠前端自行判断

---

## Task 5：观测指标

**目标**：话术三级采纳率 + 情绪刹车片触发率  
**依赖**：Task 1, Task 2  
**影响文件**：
- `src/main/java/cn/lwx/lwxaiagent/infrastructure/orchestration/ChatEntry.java`

**指标**：
- `emotion_brake.triggered`（Counter，深夜极端词触发次数）
- `emotion_brake.continued`（Counter，用户选择"继续发送"次数）
- `advice.tier.choice`（Counter，用户后续用了哪种牌 — **待讨论**：如何知道用户选择了哪种牌？）

---

## Task 6：E2E 验证

**目标**：情绪刹车片 + 话术三级全流程冒烟  
**依赖**：Task 1-5  
**影响文件**：
- `scripts/e2e-smoke.sh`（新增 7.9 恋爱辅助段）

**验证内容**：
- 情绪刹车片：深夜极端词 → 冷静提示（不阻断）
- 情绪刹车片：白天同词 → 不触发
- 话术三级：用户问"怎么回复" → 三种牌输出
- 护栏兜底：问"怎么PUA" → L3 阻断（不是话术三级）
