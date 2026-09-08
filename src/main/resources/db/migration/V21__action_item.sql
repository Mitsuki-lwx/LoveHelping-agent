-- ============================================================
-- V21: 行动卡（2026-09-08 产品闭环 ②）——把回信里的建议变成可跟踪的行动
-- 仅 INSERT/UPDATE 状态，不承载对话内容；跨会话用于"下次来信先问进展"。
-- ============================================================

CREATE TABLE IF NOT EXISTS action_item (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(50)  NOT NULL,              -- 归属（TenantContext userId）
    chat_id    VARCHAR(100),                       -- 来源会话
    content    VARCHAR(300) NOT NULL,              -- 行动内容（来自三牌建议，≤60 字）
    source     VARCHAR(20)  DEFAULT 'ADVICE',      -- ADVICE_BOLD / ADVICE_SAFE / ADVICE_RETREAT / USER
    status     VARCHAR(10)  DEFAULT 'OPEN',        -- OPEN / DONE / SKIP
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    done_at    TIMESTAMP NULL,
    INDEX idx_action_user_status (user_id, status),
    INDEX idx_action_created (created_at)
) ENGINE=InnoDB COMMENT='行动卡（回信建议落地跟踪，跨会话跟进）';
