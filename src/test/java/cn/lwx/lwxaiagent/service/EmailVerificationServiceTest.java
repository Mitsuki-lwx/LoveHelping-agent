package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.mail.MailSenderPort;
import cn.lwx.lwxaiagent.support.InMemoryRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 邮箱验证码（V33）：哈希存储 / TTL / 一次性 / 错 5 次作废 / 限流。
 *
 * <p>这些性质全在 Redis 语义里，所以用 {@link InMemoryRedis}（带真实 get/set/TTL/incr 语义的替身）
 * 而不是「返回固定值」的空 mock——否则等于什么都没测。</p>
 */
@DisplayName("EmailVerificationService：验证码存储与校验")
class EmailVerificationServiceTest {

    private static final String TARGET = "a@b.com";
    private static final Pattern CODE_IN_BODY = Pattern.compile("验证码：(\\d{6})");

    private InMemoryRedis redis;
    private MailSenderPort mail;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        redis = new InMemoryRedis();
        mail = mock(MailSenderPort.class);
        service = new EmailVerificationService(redis.template(), mail);
    }

    private String codeKey() {
        return "auth:code:" + EmailVerificationService.PURPOSE_BIND + ":" + TARGET;
    }

    /** 发码并返回真实验证码（从发出的邮件正文里取——不依赖被测类的内部实现）。 */
    private String issueAndReadCode() {
        service.issue(EmailVerificationService.PURPOSE_BIND, TARGET, TARGET);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(TARGET), anyString(), body.capture());
        Matcher m = CODE_IN_BODY.matcher(body.getValue());
        assertThat(m.find()).as("邮件正文必须含 6 位验证码").isTrue();
        return m.group(1);
    }

    // ================= 存储：哈希 + TTL =================

    /** 存进 Redis 的是 SHA-256 哈希，不是明文；TTL 恰好 5 分钟。 */
    @Test
    void issue_storesSha256Hash_notPlaintext_withFiveMinuteTtl() {
        String code = issueAndReadCode();
        String stored = redis.get(codeKey());

        assertThat(stored).isNotNull();
        assertThat(stored).isNotEqualTo(code);                                  // 明文绝不入库
        assertThat(stored).isEqualTo(EmailVerificationService.sha256(code));    // 可校验的哈希
        assertThat(stored).hasSize(64);                                          // SHA-256 hex
        assertThat(redis.ttl(codeKey())).isEqualTo(Duration.ofMinutes(5));       // TTL 5 分钟
    }

    /** 目标邮箱为空（未绑定/账号不存在路径）→ 仍然写码但不发信（防枚举的行为一致性）。 */
    @Test
    void issue_withoutEmail_stillStoresCode_butSendsNothing() {
        service.issue(EmailVerificationService.PURPOSE_RESET, "ghost", null);

        assertThat(redis.get("auth:code:reset:ghost")).isNotNull();
        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    // ================= 校验：一次性 / 过期 / 错误 =================

    /** 正确验证码 → 通过；且**一次性**，同一码第二次必然失败。 */
    @Test
    void verify_correctCode_succeedsOnce_andIsConsumed() {
        String code = issueAndReadCode();

        service.verify(EmailVerificationService.PURPOSE_BIND, TARGET, code);   // 不抛
        assertThat(redis.has(codeKey())).isFalse();                            // 用完即删

        assertThatThrownBy(() -> service.verify(EmailVerificationService.PURPOSE_BIND, TARGET, code))
                .isInstanceOf(BizException.class)
                .hasMessage(EmailVerificationService.INVALID_MESSAGE);
    }

    /** 过期（Redis 里已无该键）→ 与"错误"同一句文案（不给攻击者区分侧信道）。 */
    @Test
    void verify_expiredCode_usesSameMessageAsWrongCode() {
        String code = issueAndReadCode();
        redis.expireNow(codeKey());   // 模拟 5 分钟到期

        assertThatThrownBy(() -> service.verify(EmailVerificationService.PURPOSE_BIND, TARGET, code))
                .isInstanceOf(BizException.class)
                .hasMessage(EmailVerificationService.INVALID_MESSAGE);
    }

    /** 错 5 次 → 验证码作废：之后即使拿到正确码也必须重新获取。 */
    @Test
    void verify_fiveWrongAttempts_invalidatesCode_evenForCorrectCodeLater() {
        String code = issueAndReadCode();
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> service.verify(EmailVerificationService.PURPOSE_BIND, TARGET, wrong))
                    .isInstanceOf(BizException.class)
                    .hasMessage(EmailVerificationService.INVALID_MESSAGE);
        }
        assertThatThrownBy(() -> service.verify(EmailVerificationService.PURPOSE_BIND, TARGET, wrong))
                .isInstanceOf(BizException.class)
                .hasMessage(EmailVerificationService.TOO_MANY_ATTEMPTS_MESSAGE);

        assertThat(redis.has(codeKey())).isFalse();   // 已作废
        assertThatThrownBy(() -> service.verify(EmailVerificationService.PURPOSE_BIND, TARGET, code))
                .isInstanceOf(BizException.class);    // 正确码也不再可用
    }

    // ================= 限流 =================

    /** 同一 target 60 秒内只能发一次 → 第二次 429。 */
    @Test
    void issue_withinCooldown_isRejected429() {
        issueAndReadCode();

        BizException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.issue(EmailVerificationService.PURPOSE_BIND, TARGET, TARGET),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(429);
        assertThat(ex.getMessage()).contains("60 秒");
    }

    /** 冷却过去后仍受"每小时最多 5 次"约束：第 6 次 429。 */
    @Test
    void issue_sixthWithinHour_isRejected429() {
        for (int i = 1; i <= 5; i++) {
            redis.expireNow("auth:code:cooldown:" + EmailVerificationService.PURPOSE_BIND + ":" + TARGET);
            service.issue(EmailVerificationService.PURPOSE_BIND, TARGET, TARGET);
        }
        redis.expireNow("auth:code:cooldown:" + EmailVerificationService.PURPOSE_BIND + ":" + TARGET);

        BizException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.issue(EmailVerificationService.PURPOSE_BIND, TARGET, TARGET),
                BizException.class);

        assertThat(ex.getCode()).isEqualTo(429);
        assertThat(ex.getMessage()).contains("1 小时");
    }

    /** 限流键的窗口是 1 小时。 */
    @Test
    void rateWindow_isOneHour() {
        service.enforceRateLimit(EmailVerificationService.PURPOSE_BIND, TARGET);

        assertThat(redis.ttl("auth:code:quota:" + EmailVerificationService.PURPOSE_BIND + ":" + TARGET))
                .isEqualTo(Duration.ofHours(1));
    }
}
