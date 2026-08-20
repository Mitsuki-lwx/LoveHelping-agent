package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护栏规则（ADR-6）：外置可配置，改动不发版。pattern_type=KEYWORD（包含匹配）/REGEX（正则）。
 */
@Data
@TableName("guardrail_rule")
public class GuardrailRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("rule_id")
    private String ruleId;

    /** 1=软提示 / 2=降温 / 3=硬阻断 */
    @TableField("level")
    private Integer level;

    @TableField("pattern_type")
    private String patternType;

    @TableField("pattern")
    private String pattern;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("description")
    private String description;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
