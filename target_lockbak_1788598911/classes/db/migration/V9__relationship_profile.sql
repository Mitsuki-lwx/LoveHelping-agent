-- V9: relationship_profile（ADR-14，记忆语义通道阶段 2/3）
-- 关系档案基础设施，数据填充依赖诊断/沙盘（Phase 4）

CREATE TABLE IF NOT EXISTS relationship_profile (
    user_id VARCHAR(50) NOT NULL PRIMARY KEY,
    stage VARCHAR(32) DEFAULT NULL,
    key_people JSON DEFAULT NULL,
    preference_conflicts JSON DEFAULT NULL,
    alerts JSON DEFAULT NULL,
    last_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
