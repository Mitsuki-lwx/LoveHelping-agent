package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.SandboxMemory;
import cn.lwx.lwxaiagent.entity.SandboxSession;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphRunner;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.service.SandboxService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 沙盘模拟控制器（Phase 4，ADR-9/ADR-12）。
 */
@Slf4j
@RestController
@RequestMapping("/sandbox")
public class SandboxController {

    private final SandboxService sandboxService;
    private final GraphRunner graphRunner;
    private final cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService guardrailRuleService;

    public SandboxController(SandboxService sandboxService, GraphRunner graphRunner,
                             cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService guardrailRuleService) {
        this.sandboxService = sandboxService;
        this.graphRunner = graphRunner;
        this.guardrailRuleService = guardrailRuleService;
    }

    // ==================== 会话管理 ====================

    @PostMapping("/create")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        String channel = (String) body.getOrDefault("channel", "REALISTIC");
        Long personaId = body.get("personaId") != null
                ? ((Number) body.get("personaId")).longValue() : null;
        String customTraits = (String) body.get("customTraits");
        String relationshipStage = (String) body.get("relationshipStage");
        return Result.ok(sandboxService.createSession(userId, channel, personaId, customTraits, relationshipStage));
    }

    @GetMapping("/personas")
    public Result<?> listPersonas() {
        return Result.ok(sandboxService.listPersonas());
    }

    @GetMapping("/list")
    public Result<?> list(@RequestParam(defaultValue = "REALISTIC") String channel) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        return Result.ok(sandboxService.listUserSessions(userId));
    }

    @GetMapping("/{id}")
    public Result<?> get(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        return Result.ok(sandboxService.getSession(id, userId));
    }

    @PostMapping("/{id}/reset")
    public Result<String> reset(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        sandboxService.resetSession(id, userId);
        return Result.ok("ok");
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        sandboxService.deleteSession(id, userId);
        return Result.ok("ok");
    }

    // ==================== 沙盘对话 ====================

    @GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chat(@RequestParam Long sandboxId, @RequestParam String message) {
        String userId = TenantContext.getUserId();
        if (userId == null) throw new BizException(401, "未登录");

        // 安全（2026-09-05 高危修复 #2）：沙盘直调编排图不经 ChatEntry——补 L3 输入护栏
        // （自伤/危险指令等规则级阻断，与主链路同源），情绪刹车片对沙盘人格会话不适用。
        var gv = guardrailRuleService.check(message);
        if (gv.level() >= 3) {
            log.warn("Sandbox guardrail L3 blocked ({}): {}", gv.ruleId(),
                    message.length() > 30 ? message.substring(0, 30) : message);
            String fallback = "self_harm".equals(gv.ruleId())
                    ? "我注意到你现在的状态可能非常难受。如果你正在经历难以承受的时刻，请一定联系专业援助：全国心理援助热线 400-161-9995。你不需要独自面对。"
                    : "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
            throw new BizException(4001, fallback);
        }

        // 归属校验 + 触摸（会话仍由 SandboxService 管理）
        SandboxSession session = sandboxService.getSession(sandboxId, userId);
        sandboxService.touchSession(sandboxId);

        Map<String, Object> input = new java.util.HashMap<>();
        input.put(GraphStateKeys.MESSAGE, message);
        input.put(GraphStateKeys.CHAT_ID, String.valueOf(sandboxId));
        input.put(GraphStateKeys.USER_ID, userId);
        input.put(GraphStateKeys.SANDBOX_ID, sandboxId);
        input.put(GraphStateKeys.ADVICE, false);

        // 走编排图（沙盘节点：人设 + 记忆 + RAG），分块模拟流式（等价 AgentLoopExecutor）
        return Flux.create(sink -> {
            java.util.concurrent.CompletableFuture.supplyAsync(() -> graphRunner.run(input, String.valueOf(sandboxId)))
                    .thenAccept(result -> {
                        String output = (String) result.get(GraphStateKeys.OUTPUT);
                        for (String part : chunk(output == null ? "" : output)) {
                            sink.next(part);
                        }
                        sink.complete();
                    })
                    .exceptionally(err -> {
                        sink.next("沙盘对话出错，请稍后再试");
                        sink.complete();
                        return null;
                    });
        });
    }

    private List<String> chunk(String text) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int size = 30;
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    // ==================== 记忆注入（CAP-8,10,11）====================

    /**
     * 注入沙盘记忆（截图解析/粘贴解析/手写事实）。
     */
    @PostMapping("/{id}/memory")
    public Result<String> injectMemory(@PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        String type = (String) body.getOrDefault("type", "FACT");
        String factText = (String) body.get("factText");
        String sourceType = (String) body.getOrDefault("sourceType", "MANUAL");
        if (factText == null || factText.isBlank()) return Result.error("factText 不能为空");
        sandboxService.injectMemory(id, userId, type, factText, sourceType);
        return Result.ok("ok");
    }

    /**
     * 批量注入记忆（截图解析多条事实）。
     */
    @PostMapping("/{id}/memory/batch")
    public Result<String> injectMemories(@PathVariable Long id,
                                          @RequestBody List<Map<String, String>> memories) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        sandboxService.injectMemories(id, userId, memories);
        return Result.ok("ok");
    }

    /**
     * 查看沙盘记忆（CAP-11 用户可编辑）。
     */
    @GetMapping("/{id}/memory")
    public Result<List<SandboxMemory>> listMemories(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        return Result.ok(sandboxService.listMemories(id, userId));
    }

    /**
     * 修改记忆（CAP-11 用户可编辑）。
     */
    @PutMapping("/{sandboxId}/memory/{memoryId}")
    public Result<String> updateMemory(@PathVariable Long sandboxId,
                                        @PathVariable Long memoryId,
                                        @RequestBody Map<String, String> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        sandboxService.updateMemory(memoryId, userId, body.get("factText"));
        return Result.ok("ok");
    }

    /**
     * 删除记忆（CAP-11 用户可编辑）。
     */
    @DeleteMapping("/{sandboxId}/memory/{memoryId}")
    public Result<String> deleteMemory(@PathVariable Long sandboxId,
                                        @PathVariable Long memoryId) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        sandboxService.deleteMemory(memoryId, userId);
        return Result.ok("ok");
    }
}
