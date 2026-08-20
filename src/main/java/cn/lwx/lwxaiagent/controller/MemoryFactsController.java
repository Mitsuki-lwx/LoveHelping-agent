package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.UserMemory;
import cn.lwx.lwxaiagent.memory.MemoryStore;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户事实记忆编辑接口（ADR-14，记忆系统阶段 2）。
 * <p>
 * 用户可查看/修改/删除 AI 记住的关于自己的事实（纠错闭环，参考 toC 情感产品的"记忆气泡"）。
 * 归属校验：所有操作只作用于当前 JWT 用户（TenantContext），跨用户访问返回 403。
 * </p>
 */
@RestController
@RequestMapping("/memory/facts")
public class MemoryFactsController {

    private final MemoryStore memoryStore;

    public MemoryFactsController(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 查看我的事实记忆（含候选与活跃）。
     */
    @GetMapping
    public Result<List<UserMemory>> listFacts() {
        return Result.ok(memoryStore.listFacts(requireUserId()));
    }

    /**
     * 编辑一条事实（纠错：编辑即转正，置信度拉满）。
     * Body: {"content": "纠正后的内容"}
     */
    @PutMapping("/{id}")
    public Result<String> updateFact(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            throw new BizException(400, "content 不能为空");
        }
        if (content.length() > 500) {
            throw new BizException(400, "content 过长（最多 500 字）");
        }
        boolean ok = memoryStore.updateFact(requireUserId(), id, content);
        if (!ok) {
            throw new BizException(403, "无权修改该记忆或不存在");
        }
        return Result.ok("updated");
    }

    /**
     * 删除一条事实。
     */
    @DeleteMapping("/{id}")
    public Result<String> deleteFact(@PathVariable Long id) {
        boolean ok = memoryStore.deleteFact(requireUserId(), id);
        if (!ok) {
            throw new BizException(403, "无权删除该记忆或不存在");
        }
        return Result.ok("deleted");
    }

    private String requireUserId() {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
