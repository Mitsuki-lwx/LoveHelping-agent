package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.service.DeleteService;
import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import cn.lwx.lwxaiagent.tenant.UserService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 契约测试（2026-09-07 接口层补盲——课 2）。
 * <p>纯 Controller 层：service 全部 mock，不依赖 DB/拦截器。覆盖三类面：
 * ① 安全行为固化（注册防自封 ADMIN——把 e2e 人肉验证固化成回归）；
 * ② 响应契约（Map 成功/失败形态、BizException→{code,message}）；
 * ③ 登录态上下文（TenantContext 注入后 me/profile/password 行为）。
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;
    @MockBean
    private JwtTokenProvider jwtTokenProvider;
    @MockBean
    private DeleteService deleteService;
    @MockBean
    private AdminGuard adminGuard;

    @AfterEach
    void cleanContext() {
        TenantContext.clear();
    }

    // ================= ① 安全行为 =================

    /** 注册请求带 role=ADMIN 也必须被强制为 USER（防自封管理员，2026-09-05 高危修复 #1） */
    @Test
    void register_ignoresClientRole_alwaysForcesUser() throws Exception {
        when(userService.register(eq("u1"), eq("Passw0rd!123"), eq("default"), eq("USER")))
                .thenReturn("jwt-token");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"Passw0rd!123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role").value("USER"))          // 返回恒 USER
                .andExpect(jsonPath("$.token").value("jwt-token"));

        verify(userService).register(eq("u1"), eq("Passw0rd!123"), eq("default"), eq("USER"));
    }

    // ================= ② 参数校验与失败契约 =================

    /** 空用户名 → 200 + success:false（本接口 Map 契约，非 4xx） */
    @Test
    void register_blankUsername_returnsFailureMap() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"Passw0rd!123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("用户名和密码不能为空"));
    }

    /** 注册失败（用户名已存在）→ success:false + 原始 message */
    @Test
    void register_duplicateUsername_returnsError() throws Exception {
        when(userService.register(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"Passw0rd!123\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    /** 登录成功 → token + role 从 claims 解析 */
    @Test
    void login_success_returnsTokenAndRole() throws Exception {
        when(userService.login("u1", "Passw0rd!123")).thenReturn("jwt-token");
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(claims.get("role", String.class)).thenReturn("USER");
        when(jwtTokenProvider.parseToken("jwt-token")).thenReturn(claims);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"Passw0rd!123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    /** 登录失败（密码错误）→ success:false（不泄露具体是用户名还是密码问题） */
    @Test
    void login_wrongPassword_returnsError() throws Exception {
        when(userService.login("u1", "bad")).thenThrow(new RuntimeException("用户名或密码错误"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"bad\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    // ================= ③ 登录态上下文 =================

    /** me 未登录（无 TenantContext）→ success:false + 未登录 */
    @Test
    void me_notLoggedIn_returnsFailure() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    /** me 已登录 → 返回完整个人资料（V18 字段） */
    @Test
    void me_loggedIn_returnsProfile() throws Exception {
        TenantContext.set("default", "u1", "USER");
        User profile = new User("u1", "x", "default", "USER");
        profile.setNickname("恋恋的小树洞");
        profile.setAvatarEmoji("🌙");
        profile.setBio("正在练习好好说话");
        when(userService.findProfile("u1")).thenReturn(profile);

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("u1"))
                .andExpect(jsonPath("$.nickname").value("恋恋的小树洞"))
                .andExpect(jsonPath("$.avatarEmoji").value("🌙"))
                .andExpect(jsonPath("$.bio").value("正在练习好好说话"));
    }

    /** updateProfile 未登录 → BizException(401) → {code:401,message:未登录} 契约 */
    @Test
    void updateProfile_notLoggedIn_returns401Contract() throws Exception {
        mockMvc.perform(put("/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"x\"}"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    /** updateProfile 已登录 + 有更新 → success:true */
    @Test
    void updateProfile_loggedIn_updatesFields() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(userService.updateProfile(eq("u1"), eq("新昵称"), eq("❤️"), eq("签名"))).thenReturn(true);

        mockMvc.perform(put("/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"新昵称\",\"avatarEmoji\":\"❤️\",\"bio\":\"签名\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("资料已更新"));
    }

    /** changePassword 已登录且旧密码校验失败 → 失败信息原样返回（Map 契约） */
    @Test
    void changePassword_wrongOldPassword_returnsError() throws Exception {
        TenantContext.set("default", "u1", "USER");
        org.mockito.Mockito.doThrow(new RuntimeException("旧密码不正确"))
                .when(userService).changePassword(eq("u1"), eq("wrong"), eq("NewPassw0rd!9"));

        mockMvc.perform(put("/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong\",\"newPassword\":\"NewPassw0rd!9\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("旧密码不正确"));
    }
}
