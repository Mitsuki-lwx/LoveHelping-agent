-- ============================================================
-- V18: 个人中心——users 表扩展资料字段（2026-09-07）
-- nickname/avatar_emoji/bio 均可空：应用层兜底（昵称空=用户名，头像空=首字符）
-- ============================================================

ALTER TABLE users
    ADD COLUMN nickname       VARCHAR(64)  NULL COMMENT '昵称（个人中心展示名，空=用户名）',
    ADD COLUMN avatar_emoji   VARCHAR(16)  NULL COMMENT '头像 emoji（如 ❤️；空=用户名首字符）',
    ADD COLUMN bio            VARCHAR(200) NULL COMMENT '个性签名/一句话介绍';
