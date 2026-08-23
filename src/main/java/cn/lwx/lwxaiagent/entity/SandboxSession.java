package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 沙盘会话（Phase 4）。
 */
@Data
@TableName("sandbox_session")
public class SandboxSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("channel")
    private String channel;

    @TableField("persona_id")
    private Long personaId;

    @TableField("custom_traits")
    private String customTraits;

    @TableField("history_version")
    private Integer historyVersion;

    @TableField("drift_count")
    private Integer driftCount;

    @TableField("needs_user_confirm")
    private Integer needsUserConfirm;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("last_active_at")
    private LocalDateTime lastActiveAt;
}
