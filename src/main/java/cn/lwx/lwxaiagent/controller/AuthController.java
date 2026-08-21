package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import cn.lwx.lwxaiagent.tenant.UserService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import io.jsonwebtoken.Claims;
import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.service.DeleteService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <h1>认证控制器</h1>
 * <p>
 * 负责处理用户认证相关的 HTTP 请求，包括用户注册、登录和获取当前用户信息。
 * 这是系统的安全入口，所有需要认证的请求都依赖此控制器生成的 JWT Token。
 * </p>
 *
 * <h2>URL 前缀</h2>
 * <p>所有接口路径以 "/auth" 开头，对应类级别 @RequestMapping("/auth")。</p>
 *
 * <h2>认证流程</h2>
 * <ol>
 *   <li>用户通过 <b>/auth/register</b> 注册账号（首次使用）</li>
 *   <li>用户通过 <b>/auth/login</b> 登录，获取 JWT Token</li>
 *   <li>后续请求在 HTTP Header 中携带 Token（如 Authorization: Bearer &lt;token&gt;）</li>
 *   <li>可通过 <b>/auth/me</b> 验证 Token 是否有效并获取用户信息</li>
 * </ol>
 *
 * <h2>多租户支持</h2>
 * <p>
 * 注册时需要指定 tenantId（租户 ID），实现数据隔离。不同租户的用户数据完全隔离，
 * 同一用户名可以在不同租户下独立注册。
 * </p>
 *
 * <h2>依赖服务</h2>
 * <ul>
 *   <li>{@link UserService}：用户服务，处理用户注册和登录的核心业务逻辑</li>
 *   <li>{@link JwtTokenProvider}：JWT Token 提供者，负责 Token 的生成和解析</li>
 *   <li>{@link TenantContext}：租户上下文，用于获取当前请求的租户和用户信息</li>
 * </ul>
 *
 * @author lwx
 * @version 1.0
 * @see UserService
 * @see JwtTokenProvider
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    /**
     * 用户服务
     * <p>通过 @Resource 注入，负责用户注册和登录的核心业务逻辑，
     * 包括密码加密、用户存储、Token 生成等。</p>
     */
    @Resource
    private UserService userService;

    /**
     * JWT Token 提供者
     * <p>通过 @Resource 注入，负责 JWT Token 的生成、解析和验证。
     * Token 中包含用户 ID、租户 ID 和角色信息。</p>
     */
    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @Resource
    private DeleteService deleteService;

    @Resource
    private AdminGuard adminGuard;

    /**
     * <h3>用户注册接口</h3>
     * <p>
     * 创建新的用户账号。注册成功后直接返回 JWT Token，用户无需再单独登录。
     * 支持多租户和角色分配。
     * </p>
     *
     * <p><b>HTTP 方法：</b>POST</p>
     * <p><b>请求路径：</b>/auth/register</p>
     * <p><b>Content-Type：</b>application/json</p>
     *
     * <p><b>请求体 JSON 参数：</b></p>
     * <ul>
     *   <li><b>username</b>（必填）：用户名，不能为空或空白字符串</li>
     *   <li><b>password</b>（必填）：密码，不能为空或空白字符串</li>
     *   <li><b>tenantId</b>（可选，默认值 "default"）：租户 ID，用于数据隔离</li>
     *   <li><b>role</b>（可选，默认值 "USER"）：用户角色，如 "USER"、"ADMIN" 等</li>
     * </ul>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * // 注册成功
     * {"success": true, "token": "eyJhbGc...", "username": "zhangsan", "role": "USER"}
     *
     * // 注册失败（用户名已存在）
     * {"success": false, "message": "用户名已存在"}
     *
     * // 参数校验失败
     * {"success": false, "message": "用户名和密码不能为空"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>新用户首次注册</li>
     *   <li>管理员为其他用户创建账号</li>
     * </ul>
     *
     * @param body 请求体，包含 username、password、tenantId（可选）、role（可选）
     * @return Map 包含 success（是否成功）、token（成功后返回）、username、role，
     *         或失败时的 message（错误描述）
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        // 从请求体中提取参数
        String username = body.get("username");
        String password = body.get("password");
        // 获取租户 ID，未提供时使用默认值 "default"
        String tenantId = body.getOrDefault("tenantId", "default");
        // 获取角色，未提供时默认分配 "USER" 角色
        String role = body.getOrDefault("role", "USER");

        // 参数校验：用户名和密码不能为空
        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            return Map.of("success", false, "message", "用户名和密码不能为空");
        }

        try {
            // 调用用户服务进行注册，返回 JWT Token
            String token = userService.register(username, password, tenantId, role);
            return Map.of("success", true, "token", token, "username", username, "role", role);
        } catch (RuntimeException e) {
            // 注册失败（如用户名已存在），返回错误信息
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * <h3>用户登录接口</h3>
     * <p>
     * 验证用户凭据并返回 JWT Token。登录成功后，客户端应在后续请求的
     * Authorization 头中携带此 Token。
     * </p>
     *
     * <p><b>HTTP 方法：</b>POST</p>
     * <p><b>请求路径：</b>/auth/login</p>
     * <p><b>Content-Type：</b>application/json</p>
     *
     * <p><b>请求体 JSON 参数：</b></p>
     * <ul>
     *   <li><b>username</b>（必填）：用户名</li>
     *   <li><b>password</b>（必填）：密码</li>
     * </ul>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * // 登录成功
     * {"success": true, "token": "eyJhbGc...", "username": "zhangsan", "role": "USER"}
     *
     * // 登录失败（密码错误）
     * {"success": false, "message": "用户名或密码错误"}
     *
     * // 参数校验失败
     * {"success": false, "message": "用户名和密码不能为空"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>已注册用户登录系统</li>
     *   <li>Token 过期后重新登录获取新 Token</li>
     * </ul>
     *
     * @param body 请求体，包含 username 和 password
     * @return Map 包含 success、token、username、role，或失败时的 message
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        // 参数校验：用户名和密码不能为 null
        if (username == null || password == null) {
            return Map.of("success", false, "message", "用户名和密码不能为空");
        }

        try {
            // 调用用户服务进行登录验证，返回 JWT Token
            String token = userService.login(username, password);
            // 从 Token 中解析出用户角色信息，用于返回给客户端
            String role = "USER"; // 默认角色
            try {
                Claims claims = jwtTokenProvider.parseToken(token);
                role = claims.get("role", String.class);
            } catch (Exception ignored) {
                // Token 解析失败时使用默认角色，不影响登录流程
            }
            return Map.of("success", true, "token", token, "username", username, "role", role);
        } catch (RuntimeException e) {
            // 登录失败（如用户名不存在或密码错误），返回错误信息
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    /**
     * <h3>获取当前登录用户信息</h3>
     * <p>
     * 从请求上下文（ThreadLocal）中获取当前登录用户的信息。
     * 该接口需要请求携带有效的 JWT Token（由拦截器/过滤器预先解析并设置到 TenantContext 中）。
     * 常用于前端页面初始化时验证登录状态和获取用户基本信息。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/auth/me</p>
     *
     * <p><b>返回值示例：</b></p>
     * <pre>{@code
     * // 已登录
     * {"success": true, "username": "zhangsan", "role": "USER"}
     *
     * // 未登录（Token 无效或不存在）
     * {"success": false, "message": "未登录"}
     * }</pre>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>前端页面加载时验证用户登录状态</li>
     *   <li>获取当前用户的角色信息以控制页面权限（如显示/隐藏管理员功能）</li>
     *   <li>Token 有效性检查</li>
     * </ul>
     *
     * @return Map 包含 success、username、role（如果已登录），或未登录时的错误信息
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        // 从 ThreadLocal 中获取当前登录用户的 ID
        String userId = TenantContext.getUserId();
        // 从 ThreadLocal 中获取当前用户的角色
        String role = TenantContext.getRole();
        // 如果用户 ID 为 null，说明未登录或 Token 无效
        if (userId == null) {
            return Map.of("success", false, "message", "未登录");
        }
        return Map.of("success", true, "username", userId, "role", role != null ? role : "USER");
    }

    /**
     * <h3>用户自行注销（ADR-5）</h3>
     * <p>级联删除当前用户的所有数据（会话、消息、记忆、Skill、任务等），并禁用账号。</p>
     */
    @DeleteMapping("/account")
    public Map<String, Object> deleteAccount() {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        deleteService.deleteUserData(userId);
        log.info("User {} deleted own account", userId);
        return Map.of("success", true, "message", "账号已注销");
    }

    /**
     * <h3>管理员强制注销用户（ADR-5）</h3>
     */
    @DeleteMapping("/admin/account/{userId}")
    public Map<String, Object> deleteAccountByAdmin(@PathVariable String userId,
                                                     HttpServletRequest request) {
        adminGuard.check(request);
        deleteService.deleteUserData(userId);
        log.info("Admin deleted user {}", userId);
        return Map.of("success", true, "message", "用户已注销");
    }
}
