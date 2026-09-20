-- ============================================================
-- V24: guardrail_event 补第二信号字段（docs/phase7-jev-shadow）
--
-- 背景：自伤护栏的 Jev 第二信号（docs/phase7-guardrail-recall）已验证召回 3/8→8/8，
-- 但那个 0 误报是自制 12 例上的数字。开启拦截前需要一轮「影子观测」：
-- 判定、落库、不拦截，用真实流量量出概率分布与误报率。
--
-- 审计表本来就是为"误报率监控"建的（ADR-6），只是原先只有离散的 level/rule_id，
-- 不足以刻画"阈值取多少合适"——那需要概率本身。
-- ============================================================

ALTER TABLE guardrail_event
    ADD COLUMN signal_score     DECIMAL(5,4) NULL COMMENT '第二信号概率 0~1（仅 Jev 判定写入）',
    ADD COLUMN signal_threshold DECIMAL(5,4) NULL COMMENT '判定时所用阈值，便于事后复算',
    ADD INDEX idx_rule (rule_id);
