# phase22 · spec —— 护栏规则加"适用范围"维度 + 流式拦截生效

> tasks（现象 + 分叉 + 判据 J1~J6）见 `tasks.md`。**用户 2026-09-28 选定「先分离再修机制」。**

## 1. 根因（读码确认）

```java
// GuardrailRuleService
public Verdict check(String input) {           // ← 一个方法，两个用途
    for (CompiledRule r : rules) { ... }       // ← 一套规则，无"适用范围"维度
}
```

| 调用点 | 语义 | 期望行为 |
| --- | --- | --- |
| `GuardrailAdvisor:74`（`check(userText)`） | **输入侧** | 用户提到自伤 → 危险信号 → 转介 |
| `ChatEntry`（输入护栏） | **输入侧** | 同上 |
| `SandboxController:138` | **输入侧** | 同上 |
| `CheckNode:33`（`check(output)`） | **输出侧** | 助手**教唆**自伤才拦；**专业地提到**自伤不该拦 |

⛔ **同一个词在两侧含义不同**：输入侧「伤害自己」= 用户有风险；
输出侧「你最近有没有想过伤害自己？」= **专业危机干预指导**。
把一套词表套两个语义 → 输出侧必然误伤（实测 6/6）。

## 2. 数据模型：`guardrail_rule` 加 `scope`

```sql
-- V25__guardrail_rule_scope.sql
ALTER TABLE guardrail_rule ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'BOTH';
-- 兼容：既有规则一律 BOTH → **输入侧行为逐字不变**
```

取值：`BOTH`（默认）/ `INPUT` / `OUTPUT`。

### 2.1 规则调整

| 规则 | 改法 | 理由 |
| --- | --- | --- |
| 既有 `self_harm`（V4/V18 的 15 条 KEYWORD） | `scope` 改 `INPUT` | 输出侧"提到自伤词"不再触发（修 D2） |
| **新增** `self_harm_incite`（`scope=OUTPUT`） | 见下 | 输出侧只拦**教唆/鼓励** |
| 其余全部规则 | 保持 `BOTH` | **输入侧行为逐字不变**（回归底线） |

### 2.2 `self_harm_incite` 的模式（REGEX，倾向"建议/方法"语境）

```
(你可以?|不妨|不如|试试|建议你?|教你|方法|步骤|怎样|如何)[^。！？\n]{0,16}
(自杀|自残|结束自己|结束生命|了结自己|伤害自己|割腕|跳楼|轻生|想死|活不下去|离开这个世界)
```

- 必须**同句相邻**（`[^。！？\n]{0,16}` 不跨句），避免"…如何陪他？… 自杀风险"这种跨句误配
- ⚠️ **已知代价**：正则判语境**必然漏判**某些教唆形态（如"很多人最后都选了那条路"）。
  这是放宽输出侧的直接代价，**必须在结论里写明**（兜底 = 输入侧 + 词典）。

## 3. Java 改动

### 3.1 `GuardrailRuleService`

```java
public enum Scope { INPUT, OUTPUT }

/** 兼容重载：等价于 check(input, Scope.INPUT) —— 生产调用点应显式传 scope */
public Verdict check(String text) { return check(text, Scope.INPUT); }

public Verdict check(String text, Scope scope) {
    // 只取 scope==BOTH 或 scope==本侧 的规则
    for (CompiledRule r : rules) {
        if (r.scope() != Scope.BOTH && r.scope() != scope) continue;
        ...
    }
}
```
- `CompiledRule` 增加 `scope` 字段（加载时从 DB 读，非法/缺失 → `BOTH`）
- ⚠️ 无参重载**必须保留**：`GuardrailAdvisor` / `ChatEntry` / `SandboxController` 三处输入侧调用**不改**
  （少改一处就少一个回归面；但也因此**必须在文档写明"默认是 INPUT"**）

### 3.2 `CheckNode`

- `check(output)` → `check(output, Scope.OUTPUT)`
- 替换文案**中性化**（修 D3）：不再假设"是你" ——
  ```
  "这个话题我需要谨慎处理。如果你或你身边的人正在经历难以承受的时刻，
   请联系专业援助：全国心理援助热线 400-161-9995，北京心理危机研究与干预中心 010-82951332。"
  ```

### 3.3 ⭐ 修 D1：流式路径让拦截真的生效

**位置**：`StreamRegistry.StreamSink`（一处拦截，覆盖全部 append 入口）

```java
// StreamSink 增加
private final StringBuilder guardrailWindow = new StringBuilder();  // 尾部窗口（跨 chunk 关键词）
private volatile boolean guardrailBlocked;

/** 推正文：返回是否真的推出去了（被护栏拦则 false） */
public boolean append(String chunk) {
    if (guardrailBlocked) return false;          // 已拦：后续 chunk 全部丢弃
    if (guardrailHits(window + chunk)) {         // ⭐ 先预演，再决定推不推
        guardrailBlocked = true;
        doAppend(REPLACEMENT_TEXT);              // 推替换文案，不推违规 chunk
        return false;
    }
    return doAppend(chunk);
}
```

- **预演检测**（`window + chunk` 后再判定）保证**违规 chunk 不被推送** —— 这是"不送达"的关键
- 只维护**尾部窗口**（保留最近 32 字符）而非全文 → 关键词跨 chunk 时也能命中，且内存与耗时可控
- 检测**复用同一个 `GuardrailRuleService`**（`Scope.OUTPUT`）→ 不新增第二套判定（避免两处规则漂移）
- ⚠️ 依赖方向：`StreamRegistry`（infrastructure）→ `GuardrailRuleService`（harness.governance）。
  若形成循环依赖，改为注入 `ObjectProvider<GuardrailRuleService>` 或把"输出侧判定"抽成窄接口。

### 3.4 `ChatEntry` 的完成回调

```java
if (!stream.streamed()) { push(OUTPUT); }              // 非流式：照旧
else if (stream.guardrailBlocked()) { /* 替换文案已在 append 时推过 → 不重复推 */ }
```
- 新增 `stream.guardrailBlocked()`；`streamed()` 语义**不变**（仍表示"推过正文"）

## 4. 不做什么

- **不动输入侧规则**（`self_harm` 的 INPUT 行为逐字不变）→ J4 回归护栏
- **不动其它 level**（emotion-brake / manipulation / sandbox）
- **不启用 JEV enforce**、**不用 JEV 判输出语境**（那是 `tasks.md` §3 的丙-2，独立决策）
- **不改 `NormalChatNode` / `QuickAnswerNode`**：拦截收在 `StreamSink` 一处，
  这正是 `entrypoint-coverage-audit` 的要求（机制在**所有入口**统一生效，而不是每个节点各加一遍）

## 5. 验证

| 判据 | 装置 |
| --- | --- |
| J1 正常求助不被拦 | `scripts/probe_l3_stream.py` 三组输入 → 输出含 L3 词但**未被替换**（内容完整） |
| J2 教唆内容被拦 | 同装置新增"诱导"用例 → 用户侧**拿不到**教唆句 |
| J3 成对证据 | 装置同时打印「`L3 blocked` 次数」与「用户侧拿到什么」 |
| J4 输入侧未放松 | 现有 E2E 的"操控请求护栏阻断" + 新增输入侧 L3 用例（含 `自杀` 等词 → 4001） |
| J5 编译/单测/E2E | `bash logs/mb.sh test` + `scripts/e2e_live.py`（22 项 + 新增） |
| J6 打字机不退化 | `scripts/probe_first_token_latency.py` 对照（修前/修后首字节） |
