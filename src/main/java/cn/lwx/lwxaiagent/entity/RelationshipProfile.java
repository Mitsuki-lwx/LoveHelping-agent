package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关系档案（ADR-14，阶段 2/3 基础设施）。
 * 记录用户的关系阶段、关键人物、偏好冲突等。
 * 数据填充依赖诊断/沙盘功能（Phase 4），当前为基础设施。
 */
@Data
@TableName("relationship_profile")
public class RelationshipProfile {

    @TableId(type = IdType.INPUT)
    private String userId;

    /** 关系阶段（暧昧/热恋/磨合/异地/分手修复） */
    @TableField("stage")
    private String stage;

    /** 关键人物（JSON：[{name, role}]），脱敏 */
    @TableField("key_people")
    private String keyPeople;

    /** 偏好冲突点（JSON） */
    @TableField("preference_conflicts")
    private String preferenceConflicts;

    /** 预警事项（JSON） */
    @TableField("alerts")
    private String alerts;

    @TableField("last_updated_at")
    private LocalDateTime lastUpdatedAt;
}
