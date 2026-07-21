package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("knowledge_vote")
public class KnowledgeVote {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("session_id")
    private String sessionId;

    @TableField("message_index")
    private Integer messageIndex;

    @TableField("vote_type")
    private String voteType;

    @TableField("feedback_text")
    private String feedbackText;

    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

    public KnowledgeVote() {}

    public KnowledgeVote(String tenantId, String sessionId, Integer messageIndex, String voteType) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.messageIndex = messageIndex;
        this.voteType = voteType;
    }
}
