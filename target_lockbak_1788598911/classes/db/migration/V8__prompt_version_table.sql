CREATE TABLE IF NOT EXISTS prompt_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version VARCHAR(16) NOT NULL,
    type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_version (version)
);