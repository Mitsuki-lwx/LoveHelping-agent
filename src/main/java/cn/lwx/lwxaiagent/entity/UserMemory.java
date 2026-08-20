package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户结构化事实记忆（ADR-14，记忆系统阶段 2）。
 * 用户可查看/编辑/删除；内容为脱敏后的事实，不含私信原文。
 */
@Data
@TableName("user_memory")
public class UserMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    /**
     * PREFERENCE / FACT / EVENT / RELATION / CONCLUSION
     */
    @TableField("category")
    private String category;

    @TableField("content")
    private String content;

    /**
     * 1-10；&lt; 6 为候选态，多次命中/用户确认后转正
     */
    @TableField("confidence")
    private Integer confidence;

    /**
     * CANDIDATE / ACTIVE / DEPRECATED
     */
    @TableField("status")
    private String status;

    @TableField("source_conversation_id")
    private String sourceConversationId;

    @TableField("hit_count")
    private Integer hitCount;

    @TableField("last_hit_at")
    private LocalDateTime lastHitAt;

    @TableField("version")
    private Integer version;

    @TableField("is_edited")
    private Boolean edited;

    @TableField("ttl_days")
    private Integer ttlDays;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
