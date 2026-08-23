package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("relationship_profile")
public class RelationshipProfile {

    @TableId(type = IdType.INPUT)
    private String userId;

    @TableField("stage")
    private String stage;

    /** JSON 字段，使用 JacksonTypeHandler 自动序列化/反序列化 */
    @TableField(value = "key_people", typeHandler = JacksonTypeHandler.class)
    private String keyPeople;

    @TableField(value = "preference_conflicts", typeHandler = JacksonTypeHandler.class)
    private String preferenceConflicts;

    @TableField(value = "alerts", typeHandler = JacksonTypeHandler.class)
    private String alerts;

    @TableField("last_updated_at")
    private LocalDateTime lastUpdatedAt;
}
