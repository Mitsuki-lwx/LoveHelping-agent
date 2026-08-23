package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
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

    /**
     * 创建沙盘会话。
     */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");

        String channel = (String) body.getOrDefault("channel", "REALISTIC");
        Long personaId = body.get("personaId") != null
                ? ((Number) body.get("personaId")).longValue() : null;
        String customTraits = (String) body.get("customTraits");
        String relationshipStage = (String) body.get("relationshipStage");

        return Result.ok(sandboxService.createSession(
                userId, channel, personaId, customTraits, relationshipStage));
    }

    /**
     * 沙盘对话（流式，走 ChatExecutor 浅层 + 人格参数注入）。
     */
    @GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chat(@RequestParam Long sandboxId, @RequestParam String message) {
        String userId = TenantContext.getUserId();
        if (userId == null) throw new BizException(401, "未登录");

        SandboxSession session = sandboxService.getSession(sandboxId, userId);

        // 构建沙盘专用 prompt（注入人格参数）
        String sandboxPrompt = sandboxService.buildSandboxPrompt(session);

        // 用 ChatExecutor 浅层执行，注入沙盘系统提示词
        AgentResult result = chatExecutor.execute(message, sandboxId.toString(), CapabilitySet.plain(), sandboxPrompt);

        // 更新会话活跃时间
        sandboxService.touchSession(sandboxId);

        if (result instanceof AgentResult.ShallowResult sr) {
            return sr.flux();
        }
        return Flux.empty();
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
}
