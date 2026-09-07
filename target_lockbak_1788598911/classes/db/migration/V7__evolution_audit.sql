-- V7: 进化模块加固（审核状态前置）
-- evolution_skill 加 audit_status 列

ALTER TABLE evolution_skill
ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/APPROVED/REJECTED，审核后才可被检索';
