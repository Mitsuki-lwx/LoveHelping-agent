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

    /**
     * 适用范围（ADR-55 / V25）：{@code BOTH}（默认）/ {@code INPUT} / {@code OUTPUT}。
     *
     * <p>为什么需要它：{@code check()} 一个方法同时服务输入侧（用户原话）与输出侧（助手回复），
     * 而同一个词在两侧含义完全不同 —— 输入侧「伤害自己」= 用户有风险（要转介）；
     * 输出侧「你最近有没有想过伤害自己？」= 专业危机干预指导（不该拦）。
     * 实测 6/6 轮「如何帮助低落的朋友」的正常求助被输出侧误判为 L3。</p>
     */
    @TableField("scope")
    private String scope;

    /**
     * 前提/豁免式（ADR-59 / V28，可空）：一条 REGEX。
     *
     * <p><b>规则命中后</b>再看它：若豁免式也 {@code find()} 命中 → **本规则不算命中**。
     * 用来表达"这条裸关键词，但仅当用户**不是**在转述别人的风险 / 求助时才算命中"——
     * 例如「朋友说他想自杀，我该怎么回应」不该被 {@code self_harm} 拦。</p>
     *
     * <p>⛔ 为什么是独立一列而不是写进 {@code pattern}：15 条 {@code self_harm} 是 KEYWORD 行，
     * 要把"除第三人称框架外"塞进 pattern 就得把每条都改成带 lookaround 的正则 ——
     * 更难审、更易写错。独立一列让"什么情况下**不**拦"可单独审计与单测。</p>
     */
    @TableField("context_exclude")
    private String contextExclude;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
