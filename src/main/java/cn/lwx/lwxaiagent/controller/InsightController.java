package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.InsightRecord;
import cn.lwx.lwxaiagent.service.InsightService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话洞察控制器（非诊断，ADR-10 无风险）。
 */
@RestController
@RequestMapping("/insight")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    /** 分析粘贴的聊天记录 */
    @PostMapping("/analyze")
    public Result<Map<String, Object>> analyze(@RequestBody Map<String, String> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        String conversation = body.get("conversation");
        String sourceType = body.getOrDefault("sourceType", "PASTE");
        return Result.ok(insightService.analyze(conversation, sourceType));
    }

    /** 分析聊天截图（OCR + 洞察） */
    @PostMapping("/analyze/media")
    public Result<Map<String, Object>> analyzeMedia(@RequestBody Map<String, Long> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        Long mediaId = body.get("mediaId");
        if (mediaId == null) return Result.error("mediaId 不能为空");
        return Result.ok(insightService.analyzeFromMedia(mediaId));
    }

    /** 列出历史分析记录 */
    @GetMapping("/history")
    public Result<List<InsightRecord>> history() {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        return Result.ok(insightService.listRecords(userId));
    }

    /** 查看单条记录 */
    @GetMapping("/{id}")
    public Result<InsightRecord> get(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        return Result.ok(insightService.getRecord(id, userId));
    }

    /** 删除记录 */
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        insightService.deleteRecord(id, userId);
        return Result.ok("ok");
    }
}
