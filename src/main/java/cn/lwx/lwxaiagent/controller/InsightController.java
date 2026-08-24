package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.service.InsightService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 分析聊天记录（粘贴文本）。
     */
    @PostMapping("/analyze")
    public Result<Map<String, Object>> analyze(@RequestBody Map<String, String> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");

        String conversation = body.get("conversation");
        String sourceType = body.getOrDefault("sourceType", "PASTE");

        Map<String, Object> result = insightService.analyze(conversation, sourceType);
        return Result.ok(result);
    }
}