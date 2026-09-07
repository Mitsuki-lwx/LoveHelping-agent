-- ============================================================
-- V20: 审计日志表（2026-09-07 课3 企业级——事后取证用，append-only）
-- 只 INSERT 不 UPDATE/DELETE；敏感动作（登录/改密/注销/删数据/管理操作）记录。
-- 设计为"五元组"：谁(actor)·何时(created_at)·对什么(resource)·做了什么(action)·结果(result)
-- ============================================================

CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor      VARCHAR(50)  NOT NULL,              -- 谁（userId；未登录=anonymous）
    action     VARCHAR(50)  NOT NULL,              -- 动作（login/change_password/delete_account/sandbox_delete...）
    resource   VARCHAR(200),                       -- 对什么（HTTP URI，如 /auth/password）
    result     VARCHAR(10)  NOT NULL,              -- SUCCESS / FAIL
    detail     VARCHAR(500),                       -- 附加（失败原因等）
    ip         VARCHAR(45),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_actor (actor),
    INDEX idx_audit_action (action),
    INDEX idx_audit_time  (created_at)
) ENGINE=InnoDB COMMENT='审计日志（append-only，事后取证）';
