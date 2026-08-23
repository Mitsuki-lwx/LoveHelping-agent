package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 沙盘角色原型（ADR-12，原创角色库）。
 */
@Data
@TableName("sandbox_persona")
public class SandboxPersona {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    /** 原型标签：傲娇/天然呆/三无/元气/温柔/霸道/知性/阳光 */
    @TableField("archetype")
    private String archetype;

    /** JSON: {tone, catchphrase, relationshipStage, ...} */
    @TableField("traits_json")
    private String traitsJson;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("is_custom")
    private Boolean isCustom;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
