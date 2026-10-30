package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.tenant.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * <h1>邮箱找回密码业务编排（V33）</h1>
 *
 * <p>把「验证码」与「用户/邮箱数据」拼成四个用例：</p>
 * <ol>
 *   <li>{@link #sendBindCode}：登录用户请求绑定邮箱 → 给该邮箱发「绑定验证码」；</li>
 *   <li>{@link #bindEmail}：校验验证码 → 落库 {@code email} + {@code email_verified_at}；</li>
 *   <li>{@link #sendResetCode}：<b>匿名</b>请求重置验证码 →
 *       <b>无论账号是否存在、是否绑了邮箱，一律"成功"且不抛异常</b>（防账号枚举）；</li>
 *   <li>{@link #resetPassword}：校验验证码 → 改密 → 令牌版本 +1（旧 token 立即失效）。</li>
 * </ol>
 *
 * <h2>为什么「重置发码」必须恒定成功</h2>
 * <p>若"账号不存在"与"验证码已发送"给出不同响应，攻击者就能用它批量探测
 * 哪些用户名真实存在（用户枚举）。因此：</p>
 * <ul>
 *   <li>接口文案恒定（见 Controller 常量）；</li>
 *   <li>未绑定邮箱时<b>仍然写入一个一次性验证码</b>（只是不发信）——见
 *       {@link EmailVerificationService#issue}，这样后续校验（含错误次数）行为与真实账号完全一致，
 *       连"验证码不存在"这种细微差异都不留；</li>
 *   <li>限流对两种情形一视同仁。</li>
 * </ul>
 */
@Slf4j
@Service
public class EmailRecoveryService {

    /**
     * 邮箱格式（保守校验）：本地部分 + @ + 域名 + 顶级域。
     * <p>不做 RFC 5322 全解析——真正的"邮箱是否归本人"由验证码证明，这里只拦明显垃圾输入。</p>
     */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** 与数据库列一致（VARCHAR(190)）。 */
    private static final int MAX_EMAIL_LENGTH = 190;

    private final UserService userService;
    private final EmailVerificationService codes;

    public EmailRecoveryService(UserService userService, EmailVerificationService codes) {
        this.userService = userService;
        this.codes = codes;
    }

    /**
     * 发送「绑定邮箱」验证码（需登录）。
     *
     * @throws BizException 400：邮箱格式非法 / 该邮箱已被其他账号绑定；429：发送过于频繁
     */
    public void sendBindCode(String username, String email) {
        String normalized = normalizeEmail(email);
        if (userService.emailTaken(normalized, username)) {
            throw new BizException(400, "该邮箱已被其他账号绑定");
        }
        codes.issue(EmailVerificationService.PURPOSE_BIND, normalized, normalized);
    }

    /**
     * 校验验证码并绑定邮箱（需登录）。
     *
     * @return 归一化后的邮箱（已落库）
     * @throws BizException 400：邮箱非法 / 已被占用 / 验证码错误或失效
     */
    public String bindEmail(String username, String email, String code) {
        String normalized = normalizeEmail(email);
        if (userService.emailTaken(normalized, username)) {
            throw new BizException(400, "该邮箱已被其他账号绑定");
        }
        codes.verify(EmailVerificationService.PURPOSE_BIND, normalized, code);
        return userService.bindEmail(username, normalized);
    }

    /**
     * 发送「重置密码」验证码（匿名）。
     *
     * <p>⛔ <b>无论账号是否存在、是否绑定邮箱，都不抛异常、文案一致</b>（防账号枚举）。
     * 未绑定邮箱时只写码不发信（{@link EmailVerificationService#issue} 的 email 为空分支）。</p>
     */
    public void sendResetCode(String username) {
        User user = userService.findByUsername(username);
        String email = verifiedEmailOrNull(user);
        if (email == null) {
            log.info("密码重置验证码请求：账号不存在或未绑定已验证邮箱 → 静默处理（防账号枚举）：username={}",
                    username);
        }
        // 注意：即使 email 为空也必须走 issue()——限流与「一次性验证码」状态对两种情形完全一致，
        //       否则"验证码不存在/错误次数不累计"会成为新的枚举侧信道。
        codes.issue(EmailVerificationService.PURPOSE_RESET, username, email);
    }

    /**
     * 校验验证码并重置密码（匿名）。成功后该用户<b>所有旧 token 立即失效</b>。
     *
     * @throws BizException 400：验证码错误/失效/超次；新密码不合规
     */
    public void resetPassword(String username, String code, String newPassword) {
        User user = userService.findByUsername(username);
        // 先校验验证码：账号不存在时行为与"验证码错误"完全一致（同一个统一文案），不泄露存在性
        codes.verify(EmailVerificationService.PURPOSE_RESET, username, code);
        if (user == null) {
            log.warn("密码重置：账号不存在但验证码校验通过（不应发生）：username={}", username);
            throw new BizException(400, EmailVerificationService.INVALID_MESSAGE);
        }
        userService.resetPassword(username, newPassword);   // 内部 tokenVersionService.bump()
    }

    /** 已绑定且已验证的邮箱；否则 null（未绑定 / 未验证 / 账号不存在）。 */
    private static String verifiedEmailOrNull(User user) {
        if (user == null) return null;
        String email = user.getEmail();
        if (email == null || email.isBlank()) return null;
        return user.getEmailVerifiedAt() == null ? null : email;
    }

    /**
     * 归一化并校验邮箱：去空白 + 转小写（域名/本地部分大小写不敏感，
     * 避免 {@code A@x.com} 与 {@code a@x.com} 在唯一索引下意外冲突）。
     *
     * @throws BizException 400：为空、过长或格式非法
     */
    static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BizException(400, "邮箱不能为空");
        }
        String normalized = email.trim().toLowerCase();
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(normalized).matches()) {
            throw new BizException(400, "邮箱格式不正确");
        }
        return normalized;
    }
}
