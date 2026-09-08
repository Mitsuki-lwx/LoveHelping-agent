package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 行动卡（V21，2026-09-08 产品闭环 ②）：把回信建议落为可跟踪的行动项。 */
@Data
@TableName("action_item")
public class ActionItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("chat_id")
    private String chatId;

    /** 行动内容：一句具体可做的事（来自三牌建议或用户自填） */
    private String content;

    /** 来源：ADVICE_BOLD / ADVICE_SAFE / ADVICE_RETREAT / USER */
    private String source;

    /** OPEN / DONE / SKIP */
    private String status;

    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

    @TableField("done_at")
    private java.time.LocalDateTime doneAt;
}
