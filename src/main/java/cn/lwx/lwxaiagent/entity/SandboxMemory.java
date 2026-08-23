package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 沙盘专属记忆（Phase 4 扩展）。
 * 独立于主聊天的 user_memory，不混淆（ADR-14 红线）。
 */
@Data
@TableName("sandbox_memory")
public class SandboxMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("sandbox_id")
    private Long sandboxId;

    @TableField("user_id")
    private String userId;

    /** FACT / SPEECH_STYLE / RELATION / EVENT */
    @TableField("type")
    private String type;

    @TableField("fact_text")
    private String factText;

    /** SCREENSHOT / PASTE / MANUAL */
    @TableField("source_type")
    private String sourceType;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
