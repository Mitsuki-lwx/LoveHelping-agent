-- ============================================================
-- V34: 合规面（投诉举报）+ 未成年人模式（2026-10-13）
--
-- 为什么两张东西放同一条迁移：它们是同一批合规需求的**同一刀**——
--   投诉入口是"用户可申诉"的义务，未成年人模式是"平台侧强制保护"的义务，
--   都属 docs 的合规范畴，一起上线、一起回滚最省事。
--
-- ① complaint（投诉举报）：
--   · user_id：提交人（= users.username）。**不设外键**：注销账号走的是
--     软删 + 物理清除（ADR-5），外键会让"注销即删用户"与"留证"互相打架；
--     投诉记录本身是合规留证，**不随用户注销而消失**。
--   · category：CONTENT（内容）/PRIVACY（隐私）/ABUSE（辱骂）/OTHER，四枚举；
--     用 VARCHAR 而不是 ENUM —— 加一类只需改 Java 常量 + 迁移注释，不用 DDL。
--   · status：OPEN（待处理）/PROCESSING（处理中）/CLOSED（已结案），默认 OPEN。
--   · content VARCHAR(1000)：与接口层校验上限一致（服务端硬校验，不依赖前端）。
--   · contact VARCHAR(120) 可空：不回填联系方式的投诉也受理。
--   · 两个索引：按人查（/complaint/mine）、按状态查（管理端列表 + 分页）。
--
-- ② users 两个新列：
--   · birth_year 可空：老数据没有出生年份，**必须可空**，否则迁移会炸/需编造数据；
--     空 = 未知，按"非未成年人"处理（保守方向：不误伤成年用户）。
--   · minor_mode 默认 0：只有注册时按出生年份判定为未满 18 才置 1；
--     服务端据此在聊天入口强制宵禁 + 每日条数上限（合规要求，不是前端开关）。
--   · evolution_consent 默认 0（关）：进化模块的**单独同意**开关（docs/07 §5.4）。
--     ⛔ 隐私默认必须是"不同意"：ReflectionScheduler 只处理已同意的用户会话，
--     未同意的用户数据一律不进知识库。没有这一列，"放开审核"= 把未同意的数据放进知识库。
--
-- 幂等性：Flyway 按版本号一次性执行（flyway_schema_history 记录），
--   重复启动不会重放本迁移；写法与既有迁移（V19/V25/V28/V31/V33）一致。
-- ============================================================

CREATE TABLE complaint (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    tenant_id  VARCHAR(50)  NOT NULL DEFAULT 'default' COMMENT '租户（ADR-13：当前固定 default）',
    user_id    VARCHAR(50)  NOT NULL COMMENT '提交人（users.username）',
    category   VARCHAR(32)  NOT NULL COMMENT 'CONTENT/PRIVACY/ABUSE/OTHER',
    content    VARCHAR(1000) NOT NULL COMMENT '投诉正文（≤1000 字，服务端硬校验）',
    contact    VARCHAR(120) NULL COMMENT '联系方式（可空：不填也受理）',
    status     VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/PROCESSING/CLOSED',
    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    INDEX idx_complaint_user (user_id),
    INDEX idx_complaint_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投诉举报（合规留证）';

ALTER TABLE users
    ADD COLUMN birth_year        INT          NULL              COMMENT '出生年份（注册时必填；老数据为空=未知）',
    ADD COLUMN minor_mode        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '未成年人模式：1=启用（注册时按出生年份判定）',
    ADD COLUMN evolution_consent TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '进化模块单独同意：1=同意（默认 0=不同意，隐私默认关）';
