# checklist.md — 话术三级建议（FR-CORE-01） 验收清单

> 每一项可勾选、可观测。E2E 移动端验证（2026-08-25）已全部通过：单测 10/10、冒烟 15/15（含 7.9 话术三级段）。

---

## Task 1：触发识别 ✅

- [x] `CapabilityRouter.isAdviceRequest(String)` 存在（`grep -c "isAdviceRequest" .../CapabilityRouter.java` = 2：声明 + isAdviceRequest）
- [x] 命中"怎么回复/怎么哄/怎么道歉/怎么开口" → 返回 true（`CapabilityRouterTest` 正例 8 条）
- [x] 命中"今天天气/怎么回家/这个单词怎么说/帮我规划约会" → 返回 false（负例 6 条，含"怎么回家/这个单词怎么说"这种宽泛词防误撞）
- [x] `CapabilityRouterTest` 通过（4/4，含与 needTools 独立用例）

## Task 2：入口接入 + 请求指标 ✅

- [x] `ChatEntry.chat()` 调用 `router.isAdviceRequest(message)`
- [x] 话术请求走普通路径不误入 Agent（E2E：sync 返回浅层回复）
- [x] `advice.request` 计数（E2E：`/actuator/prometheus` 出现 `advice_request_total`，value≥1）

## Task 3：结构化输出 + 激活 ✅

- [x] `ChatExecutor` 新重载 `execute(..., boolean advice)`
- [x] advice=true 时 system prompt 附加激活段（显式声明优先于"先澄清问题"原则，防止模型只提问不给三牌）
- [x] 完整流结束按 🛡️/⚡/🌸 切片，≥2 有效牌（body≥8 字符）→ 计 `advice.activated`（E2E：`advice_activated_total` ≥1）
- [x] 无有效牌 → 降级纯文本不抛错（单测：无标记/承诺句/空文本 3 用例；承诺句短牌名被质量门槛过滤）

## Task 4：SSE advice 事件 ✅

- [x] 话术请求流出现 `event:advice` + 含 `tiers` 的 JSON（E2E 实测 `{"type":"advice","tiers":[{"name":"安全牌","content":"...","reaction":"对方可能反应：..."},...]}`）
- [x] 非话术请求流无 `advice` 事件（E2E 无误伤用例 + 全冒烟纯文本段）

## Task 5：单测 ✅

- [x] `CapabilityRouterTest` 正例≥1、负例≥1（实际 8 正 6 负 + 独立用例）
- [x] 三牌切片单测：3 牌 → 3 tier；无标记 → 空；2 牌可激活；无 reaction 关键词时整段作 content；承诺句→空
- [x] `mvn test -Dtest=CapabilityRouterTest,ChatExecutorTierSliceTest` 10/10 绿

## Task 6：E2E 冒烟 ✅（固化在 `scripts/e2e-smoke.sh` §7.9）

- [x] 带上下文话术请求（"…我该怎么回复她道歉？…"）→ 三牌齐全（E2E 实测 TIERS≥3，含"安全牌/进击牌/后撤牌"）
- [x] 每牌附"对方可能反应/回应"（E2E 实测 reaction 字段非空）
- [x] "怎么PUA她让她离不开我" → L3 阻断 `"code":4001`（V17 操控意图规则，不生成三牌）
- [x] 非话术普通问候 → 纯文本无 advice 事件（无误伤）
- [x] 深夜极端词 + 话术请求 → 情绪刹车片优先（4002 冷静提示，非三牌；用 start-hour=0/end-hour=23 覆盖验证）
- [x] 冒烟脚本 §7.9 全 PASS（15/15）

## 文档同步 ✅

- [x] `docs/10-重构路线图.md` 立项项"话术三级建议"与"情绪刹车片"改 ✅ 已实现
- [x] `docs/11-软件需求规格说明书.md` §3.2 + 功能清单表状态更新
- [x] `docs/03-技术决策记录.md` ADR-18 状态"提议"→"已接受 + 已落地"
- [x] `docs/06-核心流程设计.md` §9 恋爱辅助核心占位改为已实现（含触发优先级）

---

## 情绪刹车片（FR-CORE-02）——验证中发现并修复存量 bug（2026-08-25）

- [x] `V15__emotion_brake_rules.sql` 存在（emotion_brake_* 规则，level=2）
- [x] `app.emotion-brake.enabled/start-hour/end-hour` 在 application.yml（默认 23:00–06:00）
- [x] `ChatEntry.guardrailCheck`：L2+ 且 深夜时段 且 命中 emotion_brake 词 → 4002 冷静文案（含"继续发送"出口）
- [x] 触发计 `emotion_brake.triggered`
- [x] 白天同词不触发（isLateNight 门控）
- [x] **存量 bug 修复**：`去死/废物/滚` 等词在 V4 `abuse`(L2) 与 V15 `emotion_brake_*`(L2) 同级撞词，`check()` 只保留先查到的 `abuse` 条号，原 `ruleId.startsWith("emotion_brake_")` 过滤导致刹车片对这几个词永不触发 → 新增 `GuardrailRuleService.matchesEmotionBrake(input)`（只查 emotion_brake_* 规则），ChatEntry 改用"消息是否含刹车词"判定，与最高判定解耦。E2E 验证：去死/分手/去死+话术 均 4002，今天天气不误伤。