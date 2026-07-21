package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.memory.MemoryService;
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

    @GetMapping("/{conversationId}")
    public Result<List<Message>> getHistory(@PathVariable String conversationId) {
        return Result.ok(memoryService.getHistory(conversationId));
    }

    @GetMapping("/{conversationId}/count")
    public Result<Map<String, Integer>> getCount(@PathVariable String conversationId) {
        return Result.ok(Map.of("count", memoryService.getMessageCount(conversationId)));
    }

    @DeleteMapping("/{conversationId}")
    public Result<String> clearHistory(@PathVariable String conversationId) {
        memoryService.clearHistory(conversationId);
        return Result.ok("cleared");
    }
}
