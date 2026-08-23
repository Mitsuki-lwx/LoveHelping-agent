package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.SandboxMemory;
import cn.lwx.lwxaiagent.entity.SandboxSession;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.infrastructure.orchestration.CapabilitySet;
import cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult;
import cn.lwx.lwxaiagent.service.SandboxService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 沙盘模拟控制器（Phase 4，ADR-9/ADR-12）。
 */
@RestController
@RequestMapping("/sandbox")
public class SandboxController {

    private final SandboxService sandboxService;
    private final ChatExecutor chatExecutor;

    public SandboxController(SandboxService sandboxService, ChatExecutor chatExecutor) {
        this.sandboxService = sandboxService;
        this.chatExecutor = chatExecutor;
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

        SandboxSession session = sandboxService.getSession(sandboxId, userId);

        // 构建沙盘 prompt（人格 + 记忆 + 动态情绪）
        String sandboxPrompt = sandboxService.buildSandboxPrompt(sandboxId, userId);

        // 走 ChatExecutor 浅层（沙盘是对话，不需要多步循环）
        AgentResult result = chatExecutor.execute(message, sandboxId.toString(), CapabilitySet.plain(), sandboxPrompt);

        sandboxService.touchSession(sandboxId);

        if (result instanceof AgentResult.ShallowResult sr) {
            return sr.flux();
        }
        return Flux.empty();
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
