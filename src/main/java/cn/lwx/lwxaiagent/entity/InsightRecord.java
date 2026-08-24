package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("insight_record")
public class InsightRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    /** PASTE / SCREENSHOT */
    @TableField("source_type")
    private String sourceType;

    @TableField("summary")
    private String summary;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
