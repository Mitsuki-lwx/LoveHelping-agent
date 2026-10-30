package cn.lwx.lwxaiagent.vo;

import java.time.LocalDateTime;

/**
 * 进化技能审核列表视图（V34 闭环补齐）。
 *
 * <p>管理端 {@code GET /evolution/skills} 用；{@code content} 是注入 LLM 的正文，
 * 审核时需要看到全文才能判断质量。</p>
 */
public record SkillVO(
        Long id,
        String skillName,
        String description,
        String content,
        Integer qualityScore,
        String auditStatus,
        Boolean isActive,
        String sourceSessionId,
        LocalDateTime createdAt) {
}
