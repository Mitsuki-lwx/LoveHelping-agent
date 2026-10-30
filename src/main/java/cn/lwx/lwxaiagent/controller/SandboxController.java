package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.audit.AuditLog;
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
    private final cn.lwx.lwxaiagent.infrastructure.orchestration.ChatEntry chatEntry;
    private final cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService guardrailRuleService;

    private final cn.lwx.lwxaiagent.service.SandboxTaViewService taViewService;
    private final cn.lwx.lwxaiagent.service.SandboxReviewService reviewService;

    public SandboxController(SandboxService sandboxService, cn.lwx.lwxaiagent.infrastructure.orchestration.ChatEntry chatEntry,
                             cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService guardrailRuleService,
                             cn.lwx.lwxaiagent.service.SandboxTaViewService taViewService,
                             cn.lwx.lwxaiagent.service.SandboxReviewService reviewService) {
        this.sandboxService = sandboxService;
        this.chatEntry = chatEntry;
        this.guardrailRuleService = guardrailRuleService;
        this.taViewService = taViewService;
        this.reviewService = reviewService;
    }

    /** 演练复盘（2026-09-08 产品闭环 ③）：旁观教练点评这场演练。 */
    @AuditLog("sandbox_review")
    @PostMapping("/{id}/review")
    public Result<Map<String, Object>> review(@PathVariable Long id) {
        if (TenantContext.getUserId() == null) return Result.error("未登录");
        var r = reviewService.review(id, TenantContext.getUserId());
        return Result.ok(Map.of(
                "summary", r.summary() == null ? "" : r.summary(),
                "good", r.good() == null ? "" : r.good(),
                "risk", r.risk() == null ? "" : r.risk(),
                "better", r.better() == null ? "" : r.better()));
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

    @AuditLog("sandbox_delete")
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        String userId = TenantContext.getUserId();
        if (userId == null) return Result.error("未登录");
        sandboxService.deleteSession(id, userId);
        return Result.ok("ok");
    }

    /**
     * TA 视角推演（2026-09-08 产品闭环 ①）：以指定人格推演"TA 会怎么回这句话"。
     * 一次性推演，不落库会话；走 L3 护栏 + 主备降级链。
     */
    @AuditLog("sandbox_ta_view")
    @PostMapping("/ta-view")
    public Result<Map<String, Object>> taView(@RequestBody Map<String, Object> body) {
        if (TenantContext.getUserId() == null) return Result.error("未登录");
        Long personaId = body.get("personaId") != null
                ? ((Number) body.get("personaId")).longValue() : null;
        String customTraits = (String) body.get("customTraits");
        String message = (String) body.get("message");
        if ((personaId == null) && (customTraits == null || customTraits.isBlank())) {
            throw new BizException(400, "请选择一个人设或填写自定义特征");
        }
        var r = taViewService.taView(personaId, customTraits, message);
        return Result.ok(Map.of("personaName", r.personaName(),
                "reply", r.reply() == null ? "" : r.reply(),
                "insight", r.insight() == null ? "" : r.insight()));
    }

    // ==================== 沙盘对话 ====================

    @GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> chat(@RequestParam Long sandboxId, @RequestParam String message,
                             @RequestParam(required = false) String mediaIds) {
        String userId = TenantContext.getUserId();
        if (userId == null) throw new BizException(401, "未登录");

        // 安全（2026-09-05 高危修复 #2）：沙盘直调编排图不经 ChatEntry——补 L3 输入护栏
        // （自伤/危险指令等规则级阻断，与主链路同源），情绪刹车片对沙盘人格会话不适用。
        var gv = guardrailRuleService.check(message);
        if (gv.level() >= 3) {
            log.warn("Sandbox guardrail L3 blocked ({}): {}", gv.ruleId(),
                    message.length() > 30 ? message.substring(0, 30) : message);
            // ⛔ 文案走 CrisisResources（唯一事实源）：此前这里内联了一份**只有一条热线**的副本，
            //    与主链路的四条副本互相漂移（ADR-89）。
            String fallback = "self_harm".equals(gv.ruleId())
                    ? cn.lwx.lwxaiagent.harness.governance.CrisisResources.referralText()
                    : cn.lwx.lwxaiagent.harness.governance.CrisisResources.BLOCK_TEXT;
            throw new BizException(4001, fallback);
        }

        // 归属校验 + 触摸（会话仍由 SandboxService 管理）
        SandboxSession session = sandboxService.getSession(sandboxId, userId);
        sandboxService.touchSession(sandboxId);

        // Shared subscription admission, total timeout, cancellation and trace propagation.
        // mediaIds 为逗号分隔（与主链 Love_app/chat/sse 同格式、同解析语义）。
        return chatEntry.sandbox(message, sandboxId, userId, parseMediaIds(mediaIds))
                .onErrorResume(e -> Flux.just(e instanceof BizException b ? b.getMessage() : "沙盘对话出错，请稍后再试"));
    }

    /** 解析 mediaIds 字符串为 Long 列表（逗号分隔，空白过滤）——语义与主链 AiController.parseMediaIds 一致。 */
    private List<Long> parseMediaIds(String mediaIds) {
        if (mediaIds == null || mediaIds.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(mediaIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
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
