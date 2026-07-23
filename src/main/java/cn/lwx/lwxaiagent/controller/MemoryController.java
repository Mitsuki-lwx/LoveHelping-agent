package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.memory.MemoryService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** 注册对话归属 */
    @PostMapping("/register")
    public Result<String> registerConversation(@RequestBody Map<String, String> body) {
        String userId = TenantContext.getUserId();
        if (userId == null) userId = "anonymous";
        String conversationId = body.get("conversationId");
        String title = body.get("title");
        String chatType = body.getOrDefault("chatType", "love");
        if (conversationId == null) {
            return Result.error("conversationId is required");
        }
        memoryService.registerConversation(userId, conversationId, title, chatType);
        return Result.ok("ok");
    }

    /** 列出当前用户的对话记录（按类型筛选） */
    @GetMapping("/conversations")
    public Result<List<Map<String, Object>>> listConversations(
            @RequestParam(defaultValue = "love") String chatType) {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            return Result.ok(List.of());
        }
        return Result.ok(memoryService.listUserConversations(userId, chatType));
    }

    /** 管理员：列出所有对话 */
    @GetMapping("/admin/conversations")
    public Result<List<Map<String, Object>>> listAllConversations() {
        return Result.ok(memoryService.listAllConversations());
    }

    /** 查看对话历史 */
    @GetMapping("/{conversationId}")
    public Result<List<Message>> getHistory(@PathVariable String conversationId) {
        return Result.ok(memoryService.getHistory(conversationId));
    }

    /** 对话消息数 */
    @GetMapping("/{conversationId}/count")
    public Result<Map<String, Integer>> getCount(@PathVariable String conversationId) {
        return Result.ok(Map.of("count", memoryService.getMessageCount(conversationId)));
    }

    /** 删除对话 */
    @DeleteMapping("/{conversationId}")
    public Result<String> clearHistory(@PathVariable String conversationId) {
        memoryService.clearHistory(conversationId);
        return Result.ok("cleared");
    }
}
