package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mail.MailSenderPort;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import cn.lwx.lwxaiagent.service.EmailRecoveryService;
import cn.lwx.lwxaiagent.service.EmailVerificationService;
import cn.lwx.lwxaiagent.service.TokenVersionService;
import cn.lwx.lwxaiagent.support.InMemoryRedis;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import cn.lwx.lwxaiagent.tenant.interceptor.TenantInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h1>找回密码的安全底线：重置后旧 token 立即失效（V33）</h1>
 *
 * <p>这条链路上全部用<b>真实实现</b>（真实 JwtTokenProvider / TokenVersionService /
 * EmailVerificationService / UserService / EmailRecoveryService / TenantInterceptor），
 * 只有 Redis 用带真实语义的内存替身、邮件与数据库用 mock。</p>
 *
 * <p>验证的是端到端行为，不是某个类的方法被调用：</p>
 * <ol>
 *   <li>用户持有旧 token（tv=0）；</li>
 *   <li>走真实的「请求重置验证码 → 用邮件里的码重置密码」流程；</li>
 *   <li>旧 token 再访问受保护接口 → <b>必须 401</b>（否则"找回"是假的：攻击者旧 token 还能用满 24h）；</li>
 *   <li>按新版本重新登录拿到的 token → 正常放行。</li>
 * </ol>
 */
@DisplayName("V33：重置密码后旧 token 立即 401")
class PasswordResetInvalidatesTokenTest {

    private static final String USERNAME = "u1";
    private static final String EMAIL = "a@b.com";
    private static final Pattern CODE_IN_BODY = Pattern.compile("验证码：(\\d{6})");

    @AfterEach
    void clean() {
        TenantContext.clear();
    }

    @Test
    void oldToken_isRejectedAfterPasswordReset_newTokenStillWorks() throws Exception {
        // ---- 真实组件装配（只有 Redis/邮件/DB 是替身）----
        InMemoryRedis redis = new InMemoryRedis();
        UserMapper userMapper = mock(UserMapper.class);
        MailSenderPort mail = mock(MailSenderPort.class);

        User stored = new User(USERNAME, new BCryptPasswordEncoder().encode("OldPassw0rd!"), "default", "USER");
        stored.setEmail(EMAIL);
        stored.setEmailVerifiedAt(LocalDateTime.now());
        stored.setTokenVersion(0);
        when(userMapper.selectOne(any())).thenReturn(stored);

        TokenVersionService versions = new TokenVersionService(redis.template(), userMapper);
        EmailVerificationService codes = new EmailVerificationService(redis.template(), mail);
        UserService userService = new UserService(userMapper);
        ReflectionTestUtils.setField(userService, "tokenVersionService", versions);
        EmailRecoveryService recovery = new EmailRecoveryService(userService, codes);

        JwtTokenProvider jwt = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwt, "secret", "0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(jwt, "expirationMs", 86_400_000L);
        jwt.init();

        TenantInterceptor interceptor = new TenantInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtTokenProvider", jwt);
        ReflectionTestUtils.setField(interceptor, "tokenVersionService", versions);

        // ---- 1) 重置前签发的 token（tv=0）----
        String oldToken = jwt.generateToken(USERNAME, "default", "USER", 0L);
        assertThat(callProtected(interceptor, oldToken).getStatus()).isEqualTo(200);   // 之前可用

        // ---- 2) 真实的「发重置码 → 改密」流程 ----
        recovery.sendResetCode(USERNAME);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(EMAIL), anyString(), body.capture());
        Matcher matcher = CODE_IN_BODY.matcher(body.getValue());
        assertThat(matcher.find()).as("邮件正文必须含验证码").isTrue();
        recovery.resetPassword(USERNAME, matcher.group(1), "NewPassw0rd!9");

        // ---- 3) 旧 token 必须被拒（401 + 明确文案）----
        MockHttpServletResponse revoked = callProtected(interceptor, oldToken);
        assertThat(revoked.getStatus()).isEqualTo(401);
        assertThat(revoked.getContentAsString()).contains("登录状态已失效，请重新登录");
        assertThat(TenantContext.getUserId()).isNull();   // 被拒请求不得留下身份

        // ---- 4) 新版本重新登录的 token 正常放行 ----
        String newToken = jwt.generateToken(USERNAME, "default", "USER", 1L);
        assertThat(callProtected(interceptor, newToken).getStatus()).isEqualTo(200);
        assertThat(TenantContext.getUserId()).isEqualTo(USERNAME);
    }

    /** 模拟一次受保护请求（GET /auth/me，无 @RequireRole）。 */
    private static MockHttpServletResponse callProtected(TenantInterceptor interceptor, String token)
            throws Exception {
        TenantContext.clear();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, new Object());
        if (allowed) {
            response.setStatus(200);   // 放行（真实链路里由 Controller 写 200）
        }
        return response;
    }
}
