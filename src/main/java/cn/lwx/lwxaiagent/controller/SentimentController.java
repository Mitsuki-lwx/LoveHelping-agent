package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.SentimentLog;
import cn.lwx.lwxaiagent.service.SentimentService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 情绪时间线（V22，2026-09-08 产品闭环 ④）。懒计算：请求时补算最近未打分与会话。 */
@RestController
@RequestMapping("/sentiment")
public class SentimentController {

    private final SentimentService sentimentService;

    public SentimentController(SentimentService sentimentService) {
        this.sentimentService = sentimentService;
    }

    /** 回信完成后前端上报本轮用户消息打分（V1 主路径：对话历史在前端） */
    @org.springframework.web.bind.annotation.PostMapping("/score")
    public Result<java.util.Map<String, Object>> score(
            @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.fail(401, "未登录");
        sentimentService.scoreText(userId, body.get("chatId"), body.get("text"));
        return Result.ok(java.util.Map.of("accepted", true));
    }

    @GetMapping("/timeline")
    public Result<List<SentimentLog>> timeline() {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.fail(401, "未登录");
        return Result.ok(sentimentService.timeline(userId));
    }
}
