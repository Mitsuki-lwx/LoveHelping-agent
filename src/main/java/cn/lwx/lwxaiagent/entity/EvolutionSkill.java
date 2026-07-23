package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 进化技能实体 —— 从对话反思中提取的可复用经验。
 * <p>
 * 存入 MySQL 做持久化，同时 description 作为 embedding 文本写入向量库
 * （Milvus + ES），供下次对话时语义检索注入。
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

    /** 什么情况下使用此技能 —— 作为向量检索的 embedding 文本 */
    private String description;

    /** 具体经验内容 —— 检索命中后注入 prompt */
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
