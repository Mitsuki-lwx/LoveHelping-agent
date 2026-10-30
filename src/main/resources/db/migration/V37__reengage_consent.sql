-- V37：回访邮件的显式同意 + 发送台账（2026-10-14，ADR-92）
--
-- 背景：给用户发邮件是**主动触达**，与"用户来找我们"完全不同。合规前提：
--   ① 显式同意（默认关）② 一键退订 ③ 未成年人一律不发 ④ 频率克制 ⑤ 不发就绝不能误发。
--
-- 为什么同意要单独一列、不复用 evolution_consent：
--   那一个是"允许用我的对话提炼通用技巧"（数据用途），这一个是"允许给我发信"（触达渠道）。
--   **两类同意不能捆绑** —— 捆绑等于用一次授权换两件事。
--
-- reengage_mail_log 既是频率台账也是审计：发成功才记（发失败不记，否则会把失败当成功、14 天不再重试）。
ALTER TABLE users ADD COLUMN reengage_consent TINYINT(1) NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS reengage_mail_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
  user_id VARCHAR(50) NOT NULL,
  email VARCHAR(190) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_reengage_user (user_id, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
