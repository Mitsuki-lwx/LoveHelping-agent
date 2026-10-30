package cn.lwx.lwxaiagent.tenant.interceptor;

import cn.lwx.lwxaiagent.service.TokenVersionService;
import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import cn.lwx.lwxaiagent.tenant.RequireRole;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TenantInterceptor RBAC 测试（2026-09-07 课3 第 2 级）+ 令牌版本校验（V33）。
 * <p>@RequireRole 三态：匿名访问注解端点→401；USER 角色访问 ADMIN 端点→403；
 * ADMIN 角色→放行；无注解方法任意角色→放行。拦截器经 ReflectionTestUtils 注入 mock JWT。
 * <p>V33：token 的 {@code tv} 与当前版本不一致 → 401「登录状态已失效，请重新登录」。
 */
class TenantInterceptorTest {

    private final TenantInterceptor interceptor = new TenantInterceptor();

    /** 假控制器：带/不带注解的方法作为 HandlerMethod 载体 */
    static class FakeController {
        @RequireRole("ADMIN")
        public void adminOnly() { }

        public void open() { }
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
    }

    private HandlerMethod adminMethod() throws Exception {
        return new HandlerMethod(new FakeController(), FakeController.class.getMethod("adminOnly"));
    }

    private HandlerMethod openMethod() throws Exception {
        return new HandlerMethod(new FakeController(), FakeController.class.getMethod("open"));
    }

    private JwtTokenProvider mockJwt() {
        return mockJwt(true);
    }

    /**
     * 注入 mock JWT + mock 令牌版本服务（V33：拦截器依赖它做撤销校验）。
     *
     * @param versionCurrent true = 版本一致（token 未被撤销）
     */
    private JwtTokenProvider mockJwt(boolean versionCurrent) {
        JwtTokenProvider p = mock(JwtTokenProvider.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("u1");
        when(p.parseToken("tok")).thenReturn(claims);
        TokenVersionService versions = mock(TokenVersionService.class);
        when(versions.isCurrent(anyString(), anyLong())).thenReturn(versionCurrent);
        ReflectionTestUtils.setField(interceptor, "tokenVersionService", versions);
        return p;
    }

    /** 匿名（无 token）访问 @RequireRole("ADMIN") 端点 → 401 拒绝 */
    @Test
    void anonymous_accessingAdminEndpoint_denied401() throws Exception {
        ReflectionTestUtils.setField(interceptor, "jwtTokenProvider", mockJwt());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, adminMethod());

        assertThat(allowed).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    /** USER 角色访问 ADMIN 端点 → 403 拒绝 */
    @Test
    void userRole_accessingAdminEndpoint_denied403() throws Exception {
        ReflectionTestUtils.setField(interceptor, "jwtTokenProvider", mockJwt());
        TenantContext.set("default", "u1", "USER");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/secret");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, adminMethod());

        assertThat(allowed).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
    }

    /** ADMIN 角色访问 ADMIN 端点 → 放行 */
    @Test
    void adminRole_accessingAdminEndpoint_allowed() throws Exception {
        ReflectionTestUtils.setField(interceptor, "jwtTokenProvider", mockJwt());
        TenantContext.set("default", "admin1", "ADMIN");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/secret");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, adminMethod());

        assertThat(allowed).isTrue();
    }

    /** 无注解方法：匿名与任意角色都放行（不破坏现有开放端点） */
    @Test
    void unannotatedMethod_anyoneAllowed() throws Exception {
        ReflectionTestUtils.setField(interceptor, "jwtTokenProvider", mockJwt());
        // 匿名
        MockHttpServletResponse anon = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/open"), anon, openMethod())).isTrue();
        // 已登录 USER
        TenantContext.set("default", "u1", "USER");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/open");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, resp, openMethod())).isTrue();
    }

    // ================= V33：令牌版本校验（撤销） =================

    /**
     * 版本不一致（改密/重置/注销后）→ 401 + 明确文案，且<b>不注入身份</b>。
     * <p>这是"找回密码必须能踢掉旧会话"的拦截器侧防线。</p>
     */
    @Test
    void revokedToken_versionMismatch_denied401WithMessage() throws Exception {
        ReflectionTestUtils.setField(interceptor, "jwtTokenProvider", mockJwt(false));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/auth/me");
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, openMethod());

        assertThat(allowed).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("登录状态已失效，请重新登录");
        assertThat(TenantContext.getUserId()).isNull();   // 被拒的请求不得留下身份
    }

    /**
     * 老 token（本功能上线前签发、没有 {@code tv} 声明）在版本仍为 0 时照常放行 ——
     * 上线本功能不会把全部在线用户踢下线；只有该用户改密/重置/注销后才失效。
     */
    @Test
    void legacyTokenWithoutTv_isTreatedAsVersionZero() throws Exception {
        JwtTokenProvider p = mock(JwtTokenProvider.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("legacy");
        when(claims.get("tv")).thenReturn(null);   // 老 token：无 tv 声明
        when(p.parseToken("tok")).thenReturn(claims);
        TokenVersionService versions = mock(TokenVersionService.class);
        when(versions.isCurrent("legacy", 0L)).thenReturn(true);
        ReflectionTestUtils.setField(interceptor, "tokenVersionService", versions);
        ReflectionTestUtils.setField(interceptor, "jwtTokenProvider", p);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/open");
        req.addHeader("Authorization", "Bearer tok");

        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), openMethod())).isTrue();
        org.mockito.Mockito.verify(versions).isCurrent("legacy", 0L);   // 缺 tv → 按 0 比对
    }
}
