package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("knowledge_entry")
public class KnowledgeEntry {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("source_session_id")
    private String sourceSessionId;

    @TableField("entry_type")
    private String entryType;

    private String label;

    private String title;

    private String content;

    private String tags;

    private Integer weight;

    @TableField("quality_score")
    private Integer qualityScore;

    @TableField("is_active")
    private Boolean isActive;

    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

    @TableField("updated_at")
    private java.time.LocalDateTime updatedAt;

    public KnowledgeEntry() {}

    public KnowledgeEntry(String tenantId, String sourceSessionId, String entryType,
                          String label, String title, String content,
                          String tags, Integer weight, Integer qualityScore) {
        this.tenantId = tenantId;
        this.sourceSessionId = sourceSessionId;
        this.entryType = entryType;
        this.label = label;
        this.title = title;
        this.content = content;
        this.tags = tags;
        this.weight = weight;
        this.qualityScore = qualityScore;
        this.isActive = true;
    }
}
