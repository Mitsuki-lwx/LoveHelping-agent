-- V35：把「反思零产出」占位行从待审队列里摘出来（2026-10-13）
--
-- 背景：`insertSkipMark` 为了 `source_session_id` 去重，会给"无有效技能"的会话插一行
--   skill_name='__reflected_no_skill__'（is_active=0，检索方过滤掉，不参与消费）。
--   但它用的是 audit_status 默认值 'PENDING' ⇒ **混进了技能审核队列**。
--   2026-10-13 实测：PENDING 13,049 条里 **4,702 条（36%）是这种占位行**，
--   管理员逐条翻页要越过三分之一的空行，而它们本来就不是候选。
--
-- 处置：给占位行一个独立状态 NO_SKILL。
--   ⛔ 不影响去重：去重查的是 `countBySessionId`（按 source_session_id，与状态无关）。
--   ⛔ 不影响检索：检索侧一律 `is_active=1`，占位行 is_active=0。
UPDATE evolution_skill
SET audit_status = 'NO_SKILL'
WHERE skill_name = '__reflected_no_skill__' AND audit_status = 'PENDING';
