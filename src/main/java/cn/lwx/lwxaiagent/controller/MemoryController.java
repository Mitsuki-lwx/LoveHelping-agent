package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.memory.MemoryService;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <h1>记忆/对话记录管理控制器</h1>
 * <p>
 * 负责管理 AI 对话的记忆和历史记录。提供对话的注册、查询、历史查看和删除功能。
 * 每个对话会话都有一个唯一的 conversationId，用于追踪和检索完整的对话历史。
 * </p>
 *
 * <h2>URL 前缀</h2>
 * <p>所有接口路径以 "/memory" 开头，对应类级别 @RequestMapping("/memory")。</p>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>对话注册</b>：将对话与会话关联，并记录对话标题和类型</li>
 *   <li><b>对话列表</b>：查询当前用户的所有对话记录（按类型过滤）</li>
 *   <li><b>管理员功能</b>：查看系统中所有对话记录</li>
 *   <li><b>历史查看</b>：获取指定对话的完整消息历史</li>
 *   <li><b>消息计数</b>：统计指定对话的消息数量</li>
 *   <li><b>对话删除</b>：清除指定对话的所有消息记录</li>
 * </ul>
 *
 * <h2>聊天类型（chatType）说明</h2>
 * <ul>
 *   <li>"love"：默认的通用聊天类型</li>
 *   <li>其他自定义类型用于区分不同场景下的对话（如客服、技术支持等）</li>
 * </ul>
 *
 * <h2>依赖服务</h2>
 * <ul>
 *   <li>{@link MemoryService}：记忆服务，封装对话记忆的存储、查询和清理逻辑</li>
 *   <li>{@link TenantContext}：租户上下文，用于获取当前请求的用户 ID</li>
 * </ul>
 *
 * @author lwx
 * @version 1.0
 * @see MemoryService
 */
@RestController
@RequestMapping("/memory")
public class MemoryController {

    /**
     * 记忆服务
     * <p>通过构造函数注入，负责对话记忆的持久化存储和检索。
     * 底层可能使用数据库、Redis 或向量数据库来实现。</p>
     */
    private final MemoryService memoryService;

    private final AdminGuard adminGuard;

    /**
     * 构造函数注入 MemoryService 与 AdminGuard
     *
     * @param memoryService 记忆服务实例，由 Spring 容器自动注入
     * @param adminGuard    管理端访问守卫，用于 /admin/** 权限校验
     */
    public MemoryController(MemoryService memoryService, AdminGuard adminGuard) {
        this.memoryService = memoryService;
        this.adminGuard = adminGuard;
    }

    /**
     * 会话归属校验（IDOR 防护）：会话已注册归属且非本人（也非 ADMIN）时抛 403。
     * 未注册归属的会话放行（匿名/未注册流程仍可读写自己的新会话）。
     */
    private void checkOwnership(String conversationId) {
        if ("ADMIN".equals(TenantContext.getRole())) {
            return;
        }
        String owner = memoryService.getOwnerUserId(conversationId);
        if (owner != null && !owner.equals(TenantContext.getUserId())) {
            throw new BizException(403, "无权访问该会话");
        }
    }

    /**
     * <h3>注册对话所有权</h3>
     * <p>
     * 将一个对话会话（conversationId）与当前用户关联。
     * 注册后，用户可以在对话列表中看到此对话，并管理其历史记录。
     * 通常在创建新的聊天会话时调用此接口。
     * </p>
     *
     * <p><b>HTTP 方法：</b>POST</p>
     * <p><b>请求路径：</b>/memory/register</p>
     * <p><b>Content-Type：</b>application/json</p>
     *
     * <p><b>请求体 JSON 参数：</b></p>
     * <ul>
     *   <li><b>conversationId</b>（必填）：对话的唯一标识符，通常由前端或聊天服务生成</li>
     *   <li><b>title</b>（可选）：对话标题，用于在前端列表中展示</li>
     *   <li><b>chatType</b>（可选，默认值 "love"）：对话类型，用于分类管理</li>
     * </ul>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * // 注册成功
     * {"code": 200, "data": "ok", "message": "success"}
     *
     * // 参数缺失
     * {"code": 500, "data": null, "message": "conversationId is required"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>前端创建新的聊天会话后，调用此接口注册对话</li>
     *   <li>将匿名用户的对话关联到登录用户</li>
     * </ul>
     *
     * @param body 请求体，包含 conversationId（必填）、title（可选）、chatType（可选）
     * @return {@link Result}&lt;{@link String}&gt; 操作结果，成功时 data 为 "ok"
     */
    @PostMapping("/register")
    public Result<String> registerConversation(@RequestBody Map<String, String> body) {
        // 获取当前登录用户 ID，未登录用户使用 "anonymous" 作为标识
        String userId = TenantContext.getUserId();
        if (userId == null) userId = "anonymous";
        // 从请求体中提取对话 ID（必填）
        String conversationId = body.get("conversationId");
        // 对话标题（可选，用于前端展示）
        String title = body.get("title");
        // 对话类型，默认 "love"
        String chatType = body.getOrDefault("chatType", "love");
        // conversationId 为必传参数
        if (conversationId == null) {
            return Result.error("conversationId is required");
        }
        // 抢注防护：会话已被他人注册时拒绝（INSERT IGNORE 会静默跳过，导致归属不明的假成功）
        String existingOwner = memoryService.getOwnerUserId(conversationId);
        if (existingOwner != null && !existingOwner.equals(userId)) {
            throw new BizException(403, "该会话已属于其他用户");
        }
        // 调用记忆服务注册对话所有权
        memoryService.registerConversation(userId, conversationId, title, chatType);
        return Result.ok("ok");
    }

    /**
     * <h3>查询当前用户的对话列表</h3>
     * <p>
     * 获取当前登录用户的所有对话记录，可按对话类型进行过滤。
     * 返回的列表按时间倒序排列（最新的对话在前）。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/memory/conversations</p>
     *
     * <p><b>请求参数：</b></p>
     * <ul>
     *   <li><b>chatType</b>（可选，默认值 "love"）：按对话类型过滤，只返回指定类型的对话</li>
     * </ul>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * // 有对话记录
     * {"code": 200, "data": [{"conversationId": "abc123", "title": "关于AI的讨论", "chatType": "love", ...}], "message": "success"}
     *
     * // 未登录或无对话记录
     * {"code": 200, "data": [], "message": "success"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>前端加载用户的对话历史列表（如侧边栏的对话列表）</li>
     *   <li>按类型筛选对话（如只看客服对话、只看通用对话）</li>
     * </ul>
     *
     * @param chatType 对话类型过滤条件，默认 "love"
     * @return {@link Result}{@code <List<Map<String, Object>>>} 对话记录列表，未登录时返回空列表
     */
    @GetMapping("/conversations")
    public Result<List<Map<String, Object>>> listConversations(
            @RequestParam(defaultValue = "love") String chatType) {
        // 获取当前登录用户 ID
        String userId = TenantContext.getUserId();
        // 未登录用户返回空列表，不报错
        if (userId == null) {
            return Result.ok(List.of());
        }
        // 查询指定类型的对话记录
        return Result.ok(memoryService.listUserConversations(userId, chatType));
    }

    /**
     * <h3>管理员查询所有对话列表</h3>
     * <p>
     * 管理员接口，返回系统中所有用户的全部对话记录（不限用户、不限类型）。
     * 该接口需要管理员权限，通常由拦截器/过滤器进行权限校验。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/memory/admin/conversations</p>
     *
     * <p><b>权限要求：</b>管理员角色（ADMIN）</p>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>管理员后台查看所有用户的对话记录</li>
     *   <li>系统监控和数据统计分析</li>
     *   <li>内容审核和合规检查</li>
     * </ul>
     *
     * @return {@link Result}{@code <List<Map<String, Object>>>} 所有用户的完整对话记录列表
     */
    @GetMapping("/admin/conversations")
    public Result<List<Map<String, Object>>> listAllConversations(jakarta.servlet.http.HttpServletRequest request) {
        // 管理端校验：ADMIN 角色 JWT 或 X-Admin-Key，否则 403
        adminGuard.check(request);
        return Result.ok(memoryService.listAllConversations());
    }

    /**
     * <h3>查看对话历史消息</h3>
     * <p>
     * 获取指定对话的完整消息历史记录，包含用户消息和 AI 回复。
     * 返回的消息列表按时间顺序排列，可用于恢复对话上下文或展示历史记录。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/memory/{conversationId}</p>
     *
     * <p><b>路径参数：</b></p>
     * <ul>
     *   <li><b>conversationId</b>：对话的唯一标识符</li>
     * </ul>
     *
     * <p><b>返回值说明：</b></p>
     * <p>返回 {@link Message} 对象列表，每个 Message 包含消息角色（用户/助手/系统）、
     * 消息内容和元数据等信息。</p>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>用户点击某个历史对话，查看完整聊天记录</li>
     *   <li>恢复之前的对话上下文以继续聊天</li>
     *   <li>导出对话记录进行分析</li>
     * </ul>
     *
     * @param conversationId 对话的唯一标识符（路径变量）
     * @return {@link Result}{@code <List<Message>>} 对话的完整消息历史列表
     */
    @GetMapping("/{conversationId}")
    public Result<List<Message>> getHistory(@PathVariable String conversationId) {
        checkOwnership(conversationId);
        return Result.ok(memoryService.getHistory(conversationId));
    }

    /**
     * <h3>查询对话消息数量</h3>
     * <p>
     * 统计指定对话中包含的消息总数（用户消息 + AI 回复）。
     * 可用于前端展示对话的活跃程度或限制对话长度。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/memory/{conversationId}/count</p>
     *
     * <p><b>路径参数：</b></p>
     * <ul>
     *   <li><b>conversationId</b>：对话的唯一标识符</li>
     * </ul>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * {"code": 200, "data": {"count": 42}, "message": "success"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>前端展示对话的消息数量统计</li>
     *   <li>判断对话是否达到上下文长度限制</li>
     *   <li>监控和日志分析</li>
     * </ul>
     *
     * @param conversationId 对话的唯一标识符（路径变量）
     * @return {@link Result}{@code <Map<String, Integer>>} 包含 count 键的 Map，值为消息数量
     */
    @GetMapping("/{conversationId}/count")
    public Result<Map<String, Integer>> getCount(@PathVariable String conversationId) {
        checkOwnership(conversationId);
        return Result.ok(Map.of("count", memoryService.getMessageCount(conversationId)));
    }

    /**
     * <h3>删除对话历史</h3>
     * <p>
     * 清除指定对话的所有消息记录。删除后该对话的历史将不可恢复。
     * 注意：此操作只清除消息内容，对话本身的注册记录可能需要单独处理。
     * </p>
     *
     * <p><b>HTTP 方法：</b>DELETE</p>
     * <p><b>请求路径：</b>/memory/{conversationId}</p>
     *
     * <p><b>路径参数：</b></p>
     * <ul>
     *   <li><b>conversationId</b>：要删除的对话的唯一标识符</li>
     * </ul>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * {"code": 200, "data": "cleared", "message": "success"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>用户手动删除不需要的对话历史</li>
     *   <li>隐私保护：清除敏感对话内容</li>
     *   <li>存储空间管理：清理过期的对话数据</li>
     * </ul>
     *
     * @param conversationId 要删除的对话的唯一标识符（路径变量）
     * @return {@link Result}&lt;{@link String}&gt; 操作结果，成功时 data 为 "cleared"
     */
    @DeleteMapping("/{conversationId}")
    public Result<String> clearHistory(@PathVariable String conversationId) {
        checkOwnership(conversationId);
        // 调用记忆服务清除指定对话的所有消息
        memoryService.clearHistory(conversationId);
        return Result.ok("cleared");
    }

    /**
     * <h3>提交单条消息反馈（LIKE/DISLIKE）</h3>
     * <p>用于对话质量观测（08 §2.2 点踩率指标的数据源）。更新 {@code message.feedback}。</p>
     *
     * <p><b>HTTP 方法：</b>POST</p>
     * <p><b>请求路径：</b>/memory/message/{messageId}/feedback?value=LIKE</p>
     *
     * @param messageId 消息 ID
     * @param value     LIKE / DISLIKE / NONE
     * @return 成功时 data 为 "ok"
     */
    @PostMapping("/message/{messageId}/feedback")
    public Result<String> feedback(@PathVariable Long messageId, @RequestParam String value) {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        memoryService.feedbackMessage(messageId, value, userId);
        return Result.ok("ok");
    }
}
