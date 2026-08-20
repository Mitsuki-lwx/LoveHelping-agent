package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话摘要记忆（ADR-14，记忆系统阶段 2）。
 * 会话超阈值后由 LLM 异步压缩生成（脱敏），用于跨会话上下文注入。
 */
@Data
@TableName("conversation_summary")
public class ConversationSummary {

    @TableId("conversation_id")
    private String conversationId;

    @TableField("user_id")
    private String userId;

    @TableField("summary")
    private String summary;

    @TableField("summary_version")
    private Integer summaryVersion;

    @TableField("last_summarized_at")
    private LocalDateTime lastSummarizedAt;
}
