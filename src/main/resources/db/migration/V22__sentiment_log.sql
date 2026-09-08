-- ============================================================
-- V22: 会话情绪记录（2026-09-08 产品闭环 ④）——按会话粒度打情绪分，
-- 时间线让用户"看见自己在变好"。懒计算：时间线请求时对未打分的历史会话补算。
-- ============================================================

CREATE TABLE IF NOT EXISTS sentiment_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(50) NOT NULL,
    chat_id      VARCHAR(100) NOT NULL,
    score        TINYINT     NOT NULL,            -- -2 ~ +2（-2 很糟 … +2 明显变好）
    reason       VARCHAR(200),                    -- 一句为什么（给用户看）
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sentiment_chat (user_id, chat_id),
    INDEX idx_sentiment_time (user_id, created_at)
) ENGINE=InnoDB COMMENT='会话情绪时间线（懒计算，每会话一条）';
