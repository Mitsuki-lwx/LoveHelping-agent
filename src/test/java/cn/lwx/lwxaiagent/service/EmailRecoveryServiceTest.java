package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.tenant.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 找回密码的编排逻辑（V33）。
 *
 * <p>核心是<b>防账号枚举</b>：重置发码对「账号不存在 / 未绑定邮箱 / 已绑定」必须走同一条路径
 * （都调 {@code issue}，只是收件人为空），否则文案或行为差异会变成用户名探测器。</p>
 */
@DisplayName("EmailRecoveryService：绑定/重置编排与防枚举")
class EmailRecoveryServiceTest {

    private UserService userService;
    private EmailVerificationService codes;
    private EmailRecoveryService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        codes = mock(EmailVerificationService.class);
        service = new EmailRecoveryService(userService, codes);
    }

    private static User userWithEmail(String email, boolean verified) {
        User user = new User("u1", "hash", "default", "USER");
        user.setEmail(email);
        user.setEmailVerifiedAt(verified ? LocalDateTime.now() : null);
        return user;
    }

    // ================= 重置发码：不泄露账号是否存在 =================

    /** 账号不存在 → 不抛异常，且仍走 issue（写入一次性验证码，只是不发信）。 */
    @Test
    void sendResetCode_unknownAccount_doesNotThrow_andIssuesCodeWithoutEmail() {
        when(userService.findByUsername("ghost")).thenReturn(null);

        service.sendResetCode("ghost");   // 不抛 = 不泄露

        verify(codes).issue(EmailVerificationService.PURPOSE_RESET, "ghost", null);
    }

    /** 账号存在但邮箱未验证 → 同样静默（行为与账号不存在一致）。 */
    @Test
    void sendResetCode_unverifiedEmail_isSilentLikeUnknownAccount() {
        when(userService.findByUsername("u1")).thenReturn(userWithEmail("a@b.com", false));

        service.sendResetCode("u1");

        verify(codes).issue(EmailVerificationService.PURPOSE_RESET, "u1", null);
    }

    /** 账号存在且邮箱已验证 → 发到该邮箱。 */
    @Test
    void sendResetCode_verifiedEmail_sendsToBoundEmail() {
        when(userService.findByUsername("u1")).thenReturn(userWithEmail("a@b.com", true));

        service.sendResetCode("u1");

        verify(codes).issue(EmailVerificationService.PURPOSE_RESET, "u1", "a@b.com");
    }

    // ================= 重置密码 =================

    /** 验证码错误 → 不落库（绝不改密码）。 */
    @Test
    void resetPassword_wrongCode_throws_andNeverChangesPassword() {
        when(userService.findByUsername("u1")).thenReturn(userWithEmail("a@b.com", true));
        org.mockito.Mockito.doThrow(new BizException(400, EmailVerificationService.INVALID_MESSAGE))
                .when(codes).verify(EmailVerificationService.PURPOSE_RESET, "u1", "000000");

        assertThatThrownBy(() -> service.resetPassword("u1", "000000", "NewPassw0rd!9"))
                .isInstanceOf(BizException.class)
                .hasMessage(EmailVerificationService.INVALID_MESSAGE);
        verify(userService, never()).resetPassword(anyString(), anyString());
    }

    /** 验证码正确 → 改密（内部会 bump 令牌版本，旧 token 失效）。 */
    @Test
    void resetPassword_correctCode_changesPassword() {
        when(userService.findByUsername("u1")).thenReturn(userWithEmail("a@b.com", true));

        service.resetPassword("u1", "123456", "NewPassw0rd!9");

        verify(codes).verify(EmailVerificationService.PURPOSE_RESET, "u1", "123456");
        verify(userService).resetPassword("u1", "NewPassw0rd!9");
    }

    // ================= 绑定邮箱 =================

    /** 邮箱已被别的账号占用 → 400，且不发验证码。 */
    @Test
    void sendBindCode_emailTaken_throws400() {
        when(userService.emailTaken("a@b.com", "u1")).thenReturn(true);

        assertThatThrownBy(() -> service.sendBindCode("u1", "a@b.com"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被其他账号绑定");
        verify(codes, never()).issue(anyString(), anyString(), anyString());
    }

    /** 绑定校验通过 → 落库的是**归一化**（小写去空白）后的邮箱。 */
    @Test
    void bindEmail_correctCode_persistsNormalizedEmail() {
        when(userService.emailTaken("a@b.com", "u1")).thenReturn(false);
        when(userService.bindEmail("u1", "a@b.com")).thenReturn("a@b.com");

        assertThat(service.bindEmail("u1", "  A@B.com ", "123456")).isEqualTo("a@b.com");

        verify(codes).verify(EmailVerificationService.PURPOSE_BIND, "a@b.com", "123456");
        verify(userService).bindEmail("u1", "a@b.com");
    }

    /** 邮箱格式非法 → 400，绝不落库（防止把垃圾写进唯一索引）。 */
    @Test
    void bindEmail_malformedEmail_throws400_andNeverWrites() {
        assertThatThrownBy(() -> service.bindEmail("u1", "not-an-email", "123456"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邮箱格式不正确");
        verify(userService, never()).bindEmail(anyString(), anyString());
    }

    /** 超长邮箱（> 190，超数据库列宽）→ 400 而不是 SQL 报错。 */
    @Test
    void normalizeEmail_tooLong_throws400() {
        String longLocal = "a".repeat(200) + "@b.com";

        assertThatThrownBy(() -> EmailRecoveryService.normalizeEmail(longLocal))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邮箱格式不正确");
    }

    /** 空邮箱 → 400（绑定路径必须给出明确提示，而不是静默）。 */
    @Test
    void normalizeEmail_blank_throws400() {
        assertThatThrownBy(() -> EmailRecoveryService.normalizeEmail("   "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("邮箱不能为空");
    }
}
