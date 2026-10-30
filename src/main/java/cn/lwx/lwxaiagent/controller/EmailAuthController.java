package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.audit.AuditLog;
import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.service.EmailRecoveryService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <h1>邮箱绑定 / 找回密码控制器（V33）</h1>
 *
 * <p>统一 {@link Result} 包装（{@code {code,message,data}}）；错误一律
 * {@link BizException}（由 {@code GlobalExceptionHandler} 转成同形状响应）。</p>
 *
 * <h2>四个接口</h2>
 * <ol>
 *   <li>{@code POST /auth/email/code} —— <b>需登录</b>，给指定邮箱发绑定验证码；</li>
 *   <li>{@code POST /auth/email/bind} —— <b>需登录</b>，校验验证码并绑定；</li>
 *   <li>{@code POST /auth/password/reset/code} —— <b>匿名</b>，给该用户名已绑定的邮箱发重置验证码
 *       （⛔ 无论账号是否存在一律返回同一句文案）；</li>
 *   <li>{@code POST /auth/password/reset} —— <b>匿名</b>，校验验证码并重置密码
 *       （成功后旧 token 立即失效）。</li>
 * </ol>
 *
 * <p>路径注册见 {@code SecurityConfig}：{@code /auth/email/**} 走 JWT 拦截器；
 * {@code /auth/password/reset**} <b>不在</b>拦截名单里（匿名可访问）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class EmailAuthController {

    /**
     * 重置发码的恒定文案：<b>账号不存在 / 未绑定邮箱 / 已发送</b> 三种情况都返回这一句，
     * 否则文案差异本身就是账号枚举的侧信道。
     */
    static final String RESET_CODE_MESSAGE = "如果该账号存在且已绑定邮箱，验证码已发送，请查收（5 分钟内有效）";

    @Resource
    private EmailRecoveryService emailRecoveryService;

    /**
     * 发送「绑定邮箱」验证码（需登录）。
     *
     * <p>请求：{@code {"email":"a@b.com"}}</p>
     * <p>响应：{@code {"code":200,"message":"验证码已发送，请查收邮箱（5 分钟内有效）","data":null}}</p>
     */
    @AuditLog("email_bind_code")
    @PostMapping("/email/code")
    public Result<Void> sendBindCode(@RequestBody(required = false) Map<String, String> body) {
        String userId = requireLogin();
        String email = body == null ? null : body.get("email");
        emailRecoveryService.sendBindCode(userId, email);
        return new Result<>(200, "验证码已发送，请查收邮箱（5 分钟内有效）", null);
    }

    /**
     * 校验验证码并绑定邮箱（需登录）。
     *
     * <p>请求：{@code {"email":"a@b.com","code":"123456"}}</p>
     * <p>响应：{@code {"code":200,"message":"邮箱绑定成功","data":{"email":"a@b.com","emailVerified":true}}}</p>
     */
    @AuditLog("email_bind")
    @PostMapping("/email/bind")
    public Result<Map<String, Object>> bindEmail(@RequestBody(required = false) Map<String, String> body) {
        String userId = requireLogin();
        String email = body == null ? null : body.get("email");
        String code = body == null ? null : body.get("code");
        String bound = emailRecoveryService.bindEmail(userId, email, code);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", bound);
        data.put("emailVerified", true);
        return new Result<>(200, "邮箱绑定成功", data);
    }

    /**
     * 发送「重置密码」验证码（匿名，防账号枚举：恒定文案）。
     *
     * <p>请求：{@code {"username":"zhangsan"}}</p>
     * <p>响应：{@code {"code":200,"message":"如果该账号存在且已绑定邮箱，验证码已发送，请查收（5 分钟内有效）","data":null}}</p>
     */
    @AuditLog("password_reset_code")
    @PostMapping("/password/reset/code")
    public Result<Void> sendResetCode(@RequestBody(required = false) Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        if (username == null || username.isBlank()) {
            throw new BizException(400, "用户名不能为空");
        }
        emailRecoveryService.sendResetCode(username.trim());
        return new Result<>(200, RESET_CODE_MESSAGE, null);
    }

    /**
     * 校验验证码并重置密码（匿名）。成功后该用户所有旧 token 立即失效。
     *
     * <p>请求：{@code {"username":"zhangsan","code":"123456","newPassword":"NewPassw0rd!9"}}</p>
     * <p>响应：{@code {"code":200,"message":"密码已重置，请使用新密码登录","data":null}}</p>
     */
    @AuditLog("password_reset")
    @PostMapping("/password/reset")
    public Result<Void> resetPassword(@RequestBody(required = false) Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String code = body == null ? null : body.get("code");
        String newPassword = body == null ? null : body.get("newPassword");
        if (username == null || username.isBlank()) {
            throw new BizException(400, "用户名不能为空");
        }
        if (code == null || code.isBlank()) {
            throw new BizException(400, "验证码不能为空");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new BizException(400, "新密码不能为空");
        }
        emailRecoveryService.resetPassword(username.trim(), code, newPassword);
        return new Result<>(200, "密码已重置，请使用新密码登录", null);
    }

    /** 需登录接口的未登录兜底（拦截器已保证 /auth/email/** 有身份，这里是纵深防御）。 */
    private String requireLogin() {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
