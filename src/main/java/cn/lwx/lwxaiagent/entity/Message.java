package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息（Phase 2 落库真源，取代 SPRING_AI_CHAT_MEMORY）。
 * <p>
 * {@code feedback} 供前端点踩/点赞（免连表）；{@code prompt_version} 归因 System Prompt 版本；
 * {@code deleted} 软删标记（注销/清理不物理删，配套定时物理清除，ADR-5）。
 * </p>
 * <p>
 * 加密约束（ADR-4，Phase 3）：当前 {content} 为明文工作列；Phase 3 加密后迁移为
 * content_encrypted（见 04 §2.2 note），content_hmac 与内容一致性校验随之启用。
 * </p>
 */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("user_id")
    private String userId;

    /** USER / ASSISTANT / SYSTEM / TOOL */
    @TableField("role")
    private String role;

    @TableField("content")
    private String content;

    @TableField("content_hmac")
    private String contentHmac;

    @TableField("prompt_version")
    private String promptVersion;

    /** NONE / LIKE / DISLIKE */
    @TableField("feedback")
    private String feedback;

    /** 0=正常，1=已删（软删） */
    @TableField("deleted")
    private Integer deleted;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
