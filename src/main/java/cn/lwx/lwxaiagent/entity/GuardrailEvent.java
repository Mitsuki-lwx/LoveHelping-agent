package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 护栏触发审计（ADR-6）：只存 content_hmac，不存原文（07 §6）。用于误报率监控。
 */
@Data
@TableName("guardrail_event")
public class GuardrailEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("level")
    private Integer level;

    @TableField("rule_id")
    private String ruleId;

    @TableField("content_hmac")
    private String contentHmac;

    /** BLOCKED（阻断）/ LOGGED（仅记录）/ SHADOW（影子观测：判定但不拦截，V24） */
    @TableField("action")
    private String action;

    /** 第二信号概率 0~1（仅 Jev 判定写入，V24）；不含内容信息，不构成新的隐私面。 */
    @TableField("signal_score")
    private java.math.BigDecimal signalScore;

    /** 判定时所用阈值，便于事后复算"这条当时为什么没拦"（V24）。 */
    @TableField("signal_threshold")
    private java.math.BigDecimal signalThreshold;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
