-- ============================================================
-- V32: 关系档案加"对方称呼"字段（partner_alias）
--
-- 为什么需要：relationship_profile 此前只有库里的数据与 MemoryStore 拼进
--   <memory_profile> 的注入，用户**没有任何界面能改它**。本迁移配合新增的
--   GET/PUT /profile/relationship，让用户可自定义"怎么称呼 TA"，保证 AI 回信
--   里的称呼与用户保持一致（partner_alias 与 stage 一样会被喂给模型）。
--
-- partner_alias 可空：空 = 用户尚未设置，MemoryStore 不渲染该行。
--
-- 幂等性：Flyway 按版本号一次性执行（flyway_schema_history 记录），
--   重复启动不会重放本迁移；写法与既有裸 ALTER 迁移（V19/V25/V28/V31）一致。
-- ============================================================

ALTER TABLE relationship_profile
    ADD COLUMN partner_alias VARCHAR(32) NULL COMMENT '用户怎么称呼对方（喂给模型的称呼；空=未设置不渲染）';
