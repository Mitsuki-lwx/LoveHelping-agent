-- ============================================================
-- V33: 邮箱绑定 + 忘记密码找回（2026-10-07）
--
-- 为什么放 users 表：邮箱是**账号属性**，与登录/改密/注销同一条认证链，
--   不引入租户维度（ADR-13：tenant_id 固定 default）。
--
-- email 可空 + 唯一索引：未绑定邮箱的老用户完全不受影响
--   （MySQL 唯一索引允许**多个 NULL**，只有真正填了邮箱的行才互斥）。
--   长度 190：utf8mb4 下 190*4=760 字节，兼容所有 InnoDB 行格式。
--
-- email_verified_at：只有**验证码校验通过**才写入（= 邮箱确实归本人所有）。
--   找回密码只认「已绑定且已验证」的邮箱；空 = 未验证。
--
-- token_version：JWT 无法撤销（无黑名单）→ 用版本号做「撤销」。
--   改密/重置密码/注销账号时 +1；JWT 里带 tv 声明，拦截器比对缓存值，
--   不一致即 401「登录状态已失效，请重新登录」。默认 0，历史 token（无 tv）视为 0。
--
-- 幂等性：Flyway 按版本号一次性执行（flyway_schema_history 记录），
--   重复启动不会重放本迁移；写法与既有裸 ALTER 迁移（V19/V25/V28/V31）一致。
-- ============================================================

ALTER TABLE users
    ADD COLUMN email             VARCHAR(190) NULL COMMENT '绑定邮箱（找回密码用；空=未绑定）',
    ADD COLUMN email_verified_at TIMESTAMP    NULL COMMENT '邮箱验证通过时间（空=未验证）',
    ADD COLUMN token_version     INT NOT NULL DEFAULT 0 COMMENT '令牌版本：改密/重置/注销时 +1，使旧 JWT 立即失效';

CREATE UNIQUE INDEX uk_users_email ON users (email);
