package cn.lwx.lwxaiagent.tenant.interceptor;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TenantInterceptor RBAC 测试（2026-09-07 课3 第 2 级）。
 * <p>@RequireRole 三态：匿名访问注解端点→401；USER 角色访问 ADMIN 端点→403；
 * ADMIN 角色→放行；无注解方法任意角色→放行。拦截器经 ReflectionTestUtils 注入 mock JWT。
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
        JwtTokenProvider p = mock(JwtTokenProvider.class);
        when(p.parseToken("tok")).thenReturn(mock(Claims.class));
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
}
