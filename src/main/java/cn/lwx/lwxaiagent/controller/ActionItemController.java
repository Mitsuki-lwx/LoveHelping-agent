package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.audit.AuditLog;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.ActionItem;
import cn.lwx.lwxaiagent.service.ActionItemService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 行动卡（V21，2026-09-08 产品闭环 ②）：把回信建议落为可跟踪行动项，
 * 下次来信由编排层注入上下文，实现"上次那件事试了吗"的跟进。
 */
@Slf4j
@RestController
@RequestMapping("/action-items")
public class ActionItemController {

    private final ActionItemService actionItemService;

    public ActionItemController(ActionItemService actionItemService) {
        this.actionItemService = actionItemService;
    }

    /** 未完成行动项列表 */
    @GetMapping
    public Result<List<ActionItem>> list() {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.fail(401, "未登录");
        return Result.ok(actionItemService.listOpen(userId));
    }

    /** 从一条 AI 回复中抽取行动项（前端收到完整回信后调用；无有效建议时不建） */
    @AuditLog("action_item_create")
    @PostMapping("/from-reply")
    public Result<Map<String, Object>> fromReply(@RequestBody Map<String, String> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.fail(401, "未登录");
        ActionItem item = actionItemService.createFromReply(userId, body.get("chatId"), body.get("replyText"));
        return Result.ok(Map.of("created", item != null,
                "item", item == null ? Map.of() : Map.of(
                        "id", item.getId(),
                        "content", item.getContent(),
                        "source", item.getSource())));
    }

    /** 标记完成（归属校验，非本人 403） */
    @PutMapping("/{id}/done")
    public Result<Map<String, Object>> done(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.fail(401, "未登录");
        boolean ok = actionItemService.markDone(userId, id);
        return ok ? Result.ok(Map.of("done", true)) : Result.fail(403, "无权操作该行动项或不存在");
    }

    /** 跳过/删除（归属校验） */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> remove(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.fail(401, "未登录");
        boolean ok = actionItemService.remove(userId, id);
        return ok ? Result.ok(Map.of("removed", true)) : Result.fail(403, "无权操作该行动项或不存在");
    }
}
