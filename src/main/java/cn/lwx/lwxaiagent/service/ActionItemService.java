package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.ActionItem;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.mapper.ActionItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 行动卡服务（V21，2026-09-08 产品闭环 ②）。
 * <p>把回信里的三牌建议落为一条可跟踪的行动项（"要不要试试"），
 * 下次来信时由 {@code ChatExecutor} 把未完成项注入上下文，实现"上次那件事试了吗"的跟进。
 *
 * <p>抽取策略：复用 {@link ChatExecutor#sliceTiers} 解析三牌（已单测覆盖），
 * 取**最具体的一张牌**作为行动项——不额外调用 LLM（零成本、零延迟）。
 */
@Slf4j
@Service
public class ActionItemService {

    private final ActionItemMapper actionItemMapper;

    public ActionItemService(ActionItemMapper actionItemMapper) {
        this.actionItemMapper = actionItemMapper;
    }

    /** 单条行动项最多字数（超出截断，避免长句不便勾选） */
    private static final int MAX_CONTENT = 60;

    /**
     * 从 AI 回复中抽取一条行动项（取第一张有效牌的"具体可说的话"）。
     *
     * @return 新建的行动项；无有效牌/已存在相同内容时返回 null
     */
    public ActionItem createFromReply(String userId, String chatId, String replyText) {
        if (userId == null || replyText == null || replyText.isBlank()) return null;

        var tiers = ChatExecutor.sliceTiers(replyText);
        if (tiers.isEmpty()) return null;

        // 优先级：进击牌（最 actionable） > 安全牌 > 后撤牌
        var picked = tiers.stream()
                .filter(t -> "进击牌".equals(t.name()))
                .findFirst()
                .or(() -> tiers.stream().findFirst())
                .orElse(null);
        if (picked == null || picked.content() == null || picked.content().isBlank()) return null;

        String content = picked.content().trim();
        // 去掉牌名前缀（含"进击牌（主动）："与"进击牌："两种形态）与引号包裹
        content = content.replaceFirst("^(进击牌|安全牌|后撤牌)（[^）]*）?[:：]\\s*", "");
        content = content.replaceFirst("^(进击牌|安全牌|后撤牌)[:：]\\s*", "");
        content = content.replaceFirst("^[「\"'“]", "").replaceAll("[」\"'”]$", "").trim();
        // 截断在句子边界（避免"我想和你"式半句）
        if (content.length() > MAX_CONTENT) {
            String head = content.substring(0, MAX_CONTENT);
            int cut = Math.max(head.lastIndexOf('。'), Math.max(head.lastIndexOf('；'), head.lastIndexOf('！')));
            content = cut > 10 ? head.substring(0, cut + 1) : head;
        }
        if (content.isBlank()) return null;
        content = content.replaceAll("^[「\"'“]", "").replaceAll("[」\"'”]$", "").trim();
        if (content.isBlank()) return null;

        // 去重：同用户已有相同未完成的行动项则不重复建
        Long dup = actionItemMapper.selectCount(new LambdaQueryWrapper<ActionItem>()
                .eq(ActionItem::getUserId, userId)
                .eq(ActionItem::getContent, content)
                .eq(ActionItem::getStatus, "OPEN"));
        if (dup != null && dup > 0) return null;

        ActionItem item = new ActionItem();
        item.setUserId(userId);
        item.setChatId(chatId);
        item.setContent(content);
        item.setSource("ADVICE_" + ("进击牌".equals(picked.name()) ? "BOLD" : "SAFE"));
        item.setStatus("OPEN");
        actionItemMapper.insert(item);
        return item;
    }

    /** 未完成行动项（按时间正序，最多 5 条） */
    public List<ActionItem> listOpen(String userId) {
        if (userId == null) return List.of();
        return actionItemMapper.selectList(new LambdaQueryWrapper<ActionItem>()
                .eq(ActionItem::getUserId, userId)
                .eq(ActionItem::getStatus, "OPEN")
                .orderByAsc(ActionItem::getId)
                .last("LIMIT 5"));
    }

    /** 标记完成（归属校验：非本人返回 false） */
    public boolean markDone(String userId, Long id) {
        ActionItem item = owned(userId, id);
        if (item == null) return false;
        item.setStatus("DONE");
        item.setDoneAt(LocalDateTime.now());
        return actionItemMapper.updateById(item) > 0;
    }

    /** 删除/跳过（归属校验） */
    public boolean remove(String userId, Long id) {
        ActionItem item = owned(userId, id);
        if (item == null) return false;
        item.setStatus("SKIP");
        return actionItemMapper.updateById(item) > 0;
    }

    private ActionItem owned(String userId, Long id) {
        if (userId == null || id == null) return null;
        ActionItem item = actionItemMapper.selectById(id);
        if (item == null) return null;
        return userId.equals(item.getUserId()) ? item : null;
    }
}
