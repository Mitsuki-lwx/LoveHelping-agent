-- V10: 优化 prompt_version 索引（08 §2.4）
-- 查询模式：SELECT version FROM prompt_version WHERE type=? ORDER BY id DESC LIMIT 1
-- 旧索引 idx_version 只覆盖 version，不覆盖 type

ALTER TABLE prompt_version DROP INDEX idx_version;
ALTER TABLE prompt_version ADD INDEX idx_type_created (type, created_at DESC);
ALTER TABLE prompt_version ADD UNIQUE INDEX idx_type_version (type, version);