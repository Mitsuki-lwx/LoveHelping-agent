package cn.lwx.lwxaiagent.vo;

import java.time.LocalDateTime;

/**
 * 投诉举报对外视图（V34）。
 *
 * <p>{@code userId} 只在<b>管理端列表</b>填充（跨用户查询需要知道是谁提交的）；
 * {@code /complaint/mine} 返回自己的记录，该字段为 {@code null}。</p>
 */
public record ComplaintVO(
        Long id,
        String category,
        String content,
        String contact,
        String status,
        LocalDateTime createdAt,
        String userId) {
}
