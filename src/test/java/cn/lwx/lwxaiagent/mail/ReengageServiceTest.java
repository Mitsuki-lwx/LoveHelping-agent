package cn.lwx.lwxaiagent.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>回访邮件（ADR-92）：合规门槛与"不发就绝不能误发"</h3>
 *
 * <p>这个功能的风险不对称：<b>少发一封没人受伤，多发一封就是骚扰</b>（甚至是给未成年人发）。
 * 所以测试的重点不是"能发"，而是<b>不该发的时候一封都不发</b>。</p>
 */
@DisplayName("ReengageService：同意 / 资格 / 退订签名 / 失败不记台账")
class ReengageServiceTest {

    private static final String SECRET = "test-secret-please-change";

    private final MailSenderPort mail = mock(MailSenderPort.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private ReengageService service(boolean enabled, int dailyLimit) {
        return new ReengageService(mail, jdbc, enabled, 7, 14, dailyLimit,
                "主题", "", SECRET, "0 30 10 * * *");
    }

    @SuppressWarnings("unchecked")
    private void givenCandidates(ReengageService.Candidate... cs) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(cs));
    }

    // ==================== 总开关 ====================

    /** ⛔ 默认关 ⇒ **一封都不发**（部署不等于开始发信）。 */
    @Test
    void disabled_sendsNothing() {
        ReengageService s = service(false, 20);

        assertThat(s.runOnce()).isZero();
        verify(mail, never()).send(anyString(), anyString(), anyString());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    /** 未配 secret ⇒ 签不出退订链接 ⇒ **不发**（发一封"退不掉"的信比不发更糟）。 */
    @Test
    void blankSecret_sendsNothing() {
        ReengageService s = new ReengageService(mail, jdbc, true, 7, 14, 20, "s", "", "   ", "0 30 10 * * *");

        assertThat(s.runOnce()).isZero();
        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    // ==================== 资格（判定在 SQL 里，这里锁住每条条件都在） ====================

    /**
     * 资格条件逐条锁死。
     *
     * <p>⛔ 这是 SQL 文本断言而非行为断言 —— 因为资格**就是**那条 SQL。它挡的是
     * "以后有人为了调试顺手删掉某个条件"（尤其 {@code minor_mode = 0}：删掉就开始给未成年人发信）。</p>
     */
    @Test
    void candidateQueryEnforcesEveryGate() {
        ReengageService s = service(true, 20);
        givenCandidates();
        s.candidates();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());

        assertThat(sql.getValue())
                .as("显式同意").contains("reengage_consent = 1")
                .as("邮箱已填且有值").contains("email IS NOT NULL")
                .as("邮箱已验证").contains("email_verified_at IS NOT NULL")
                .as("未成年人一律不发").contains("minor_mode, 0) = 0")
                .as("账号未禁用").contains("enabled, 1) = 1")
                .as("沉默门槛").contains("< ?")
                .as("频率间隔").contains("reengage_mail_log")
                .as("有界").contains("LIMIT ?");

        // 参数顺序：沉默下界、间隔下界、limit —— 与 SQL 里 ? 的出现顺序一致
        Object[] a = args.getValue();
        assertThat(a).hasSize(3);
        assertThat(a[0]).isInstanceOf(java.time.LocalDateTime.class);
        assertThat(a[1]).isInstanceOf(java.time.LocalDateTime.class);
        assertThat(a[2]).isEqualTo(20);
        // 沉默窗 7 天、间隔窗 14 天 ⇒ (now-7) 比 (now-14) **更晚**（窗口更短 = 门槛更严）
        assertThat((java.time.LocalDateTime) a[0]).isAfter((java.time.LocalDateTime) a[1]);
    }

    /** 一次最多 daily-limit 封（有界，不许一次把库发空）。 */
    @Test
    void dailyLimitIsBounded() {
        ReengageService s = service(true, 2);
        givenCandidates(
                new ReengageService.Candidate("u1", "a@x.com"),
                new ReengageService.Candidate("u2", "b@x.com"),
                new ReengageService.Candidate("u3", "c@x.com"));

        assertThat(s.runOnce()).isEqualTo(2);
        verify(mail, org.mockito.Mockito.times(2)).send(anyString(), anyString(), anyString());
    }

    // ==================== 发送 / 台账 ====================

    /** 发送成功 ⇒ 写台账（否则下一轮会重复发）。 */
    @Test
    void success_writesLog() {
        ReengageService s = service(true, 20);
        givenCandidates(new ReengageService.Candidate("u1", "a@x.com"));

        assertThat(s.runOnce()).isEqualTo(1);
        verify(jdbc).update(contains("INSERT INTO reengage_mail_log"), eq("u1"), eq("a@x.com"));
    }

    /**
     * ⛔ 发送失败 ⇒ **不写台账**，且不中断整批。
     *
     * <p>写了的后果很实在：失败被当成"已发"，14 天内不再重试 —— 把一次偶发失败永久化。</p>
     */
    @Test
    void sendFailure_doesNotWriteLog_andContinuesBatch() {
        ReengageService s = service(true, 20);
        givenCandidates(
                new ReengageService.Candidate("u1", "bad@x.com"),
                new ReengageService.Candidate("u2", "ok@x.com"));
        doThrow(new RuntimeException("SMTP 550")).when(mail).send(eq("bad@x.com"), anyString(), anyString());

        int sent = s.runOnce();

        assertThat(sent).as("失败的那封不算成功，后面那封照发").isEqualTo(1);
        verify(jdbc, never()).update(contains("INSERT INTO reengage_mail_log"), eq("u1"), anyString());
        verify(jdbc).update(contains("INSERT INTO reengage_mail_log"), eq("u2"), eq("ok@x.com"));
    }

    /** 正文里必须有退订链接，且**不含任何聊天内容**（模板只有两个占位符）。 */
    @Test
    void bodyCarriesUnsubscribeUrlOnly() {
        ReengageService s = service(true, 20);
        String body = s.render(new ReengageService.Candidate("u1", "a@x.com"));

        assertThat(body).contains("/auth/reengage/unsubscribe").contains("u1");
        assertThat(body).doesNotContain("{username}").doesNotContain("{unsubscribeUrl}");
    }

    // ==================== 退订签名 ====================

    /** 正常签名可通过。 */
    @Test
    void validTokenVerifies() {
        ReengageService s = service(true, 20);
        assertThat(s.verify("u1", s.sign("u1"))).isTrue();
    }

    /** ⛔ 篡改 userId 必须失效（否则任何人都能替别人退订 / 伪造链接）。 */
    @Test
    void tokenBoundToUserId() {
        ReengageService s = service(true, 20);
        String tokenForU1 = s.sign("u1");

        assertThat(s.verify("u2", tokenForU1)).isFalse();
        assertThat(s.verify("u1", "deadbeef")).isFalse();
    }

    /** 空 secret ⇒ 校验一律 false（fail-closed），绝不放行。 */
    @Test
    void blankSecret_neverVerifies() {
        ReengageService s = new ReengageService(mail, jdbc, true, 7, 14, 20, "s", "", "", "0 30 10 * * *");
        assertThat(s.verify("u1", s.sign("u1"))).isFalse();
    }
}
