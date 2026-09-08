package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 会话情绪记录（V22，2026-09-08 产品闭环 ④）。每会话一条。 */
@Data
@TableName("sentiment_log")
public class SentimentLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("chat_id")
    private String chatId;

    /** -2 ~ +2 */
    private Integer score;

    private String reason;

    @TableField("created_at")
    private java.time.LocalDateTime createdAt;
}
