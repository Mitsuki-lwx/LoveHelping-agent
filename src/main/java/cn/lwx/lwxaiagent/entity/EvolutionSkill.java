package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Evolution skill entity -- reusable experience extracted from conversation reflection.
 * <p>
 * Stored in MySQL for persistence, while description is used as embedding text written to vector stores
 * (Milvus + ES), for semantic retrieval in subsequent conversations.
 */
@Data
@TableName("evolution_skill")
public class EvolutionSkill {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("skill_name")
    private String skillName;

    /** When to use this skill -- used as embedding text for vector retrieval */
    private String description;

    /** Specific experience content -- injected into prompt when retrieved */
    private String content;

    @TableField("source_session_id")
    private String sourceSessionId;

    @TableField("quality_score")
    private Integer qualityScore;

    @TableField("is_active")
    private Boolean isActive;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public EvolutionSkill() {}

    public EvolutionSkill(String tenantId, String skillName, String description,
                          String content, String sourceSessionId, Integer qualityScore) {
        this.tenantId = tenantId;
        this.skillName = skillName;
        this.description = description;
        this.content = content;
        this.sourceSessionId = sourceSessionId;
        this.qualityScore = qualityScore;
        this.isActive = true;
    }
}
