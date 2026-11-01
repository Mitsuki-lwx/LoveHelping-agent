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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
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
    /** V33：TenantInterceptor 依赖令牌版本服务（拦截器随 SecurityConfig 载入 MVC 切片）。 */
    @MockBean
    private cn.lwx.lwxaiagent.service.TokenVersionService tokenVersionService;
    @MockBean
    private DeleteService deleteService;
    @MockBean
    private AdminGuard adminGuard;
    /** change security-hardening：登录失败节流 —— 漏了 @MockBean 会让整个 MVC 切片上下文起不来。 */
    @MockBean
    private cn.lwx.lwxaiagent.tenant.LoginThrottle loginThrottle;
    /** V37（ADR-92）：退订/签名校验依赖它 —— 漏了 @MockBean 会让整个 MVC 切片上下文起不来。 */
    @MockBean
    private cn.lwx.lwxaiagent.mail.ReengageService reengageService;

    @AfterEach
    void cleanContext() {
        TenantContext.clear();
    }

    // ================= ① 安全行为 =================

    /** 注册请求带 role=ADMIN 也必须被强制为 USER（防自封管理员，2026-09-05 高危修复 #1） */
    @Test
    void register_ignoresClientRole_alwaysForcesUser() throws Exception {
        when(userService.register(eq("u1"), eq("Passw0rd!123"), eq("default"), eq("USER"), eq(2000)))
                .thenReturn("jwt-token");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"Passw0rd!123\",\"role\":\"ADMIN\",\"birthYear\":2000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.role").value("USER"))          // 返回恒 USER
                .andExpect(jsonPath("$.token").value("jwt-token"));

        verify(userService).register(eq("u1"), eq("Passw0rd!123"), eq("default"), eq("USER"), eq(2000));
    }

    /** V34 合规：缺失 birthYear → service 抛 400，按统一 Result 形状返回（不被 Map 兜底吞掉）。 */
    @Test
    void register_missingBirthYear_returns400Contract() throws Exception {
        when(userService.register(any(), any(), any(), any(), any()))
                .thenThrow(new cn.lwx.lwxaiagent.common.BizException(400, "出生年份无效"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"Passw0rd!123\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("出生年份无效"));
    }

    /** V34 合规：非数字 birthYear 同样按缺失处理 → 400。 */
    @Test
    void register_nonNumericBirthYear_returns400Contract() throws Exception {
        when(userService.register(eq("u1"), any(), any(), any(), isNull()))
                .thenThrow(new cn.lwx.lwxaiagent.common.BizException(400, "出生年份无效"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"Passw0rd!123\",\"birthYear\":\"去年\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("出生年份无效"));
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
        when(userService.register(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"password\":\"Passw0rd!123\",\"birthYear\":2000}"))
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

    /** me 已登录 → 返回完整个人资料（V18 字段 + V31 纪念日） */
    @Test
    void me_loggedIn_returnsProfile() throws Exception {
        TenantContext.set("default", "u1", "USER");
        User profile = new User("u1", "x", "default", "USER");
        profile.setNickname("恋恋的小树洞");
        profile.setAvatarEmoji("🌙");
        profile.setBio("正在练习好好说话");
        profile.setAnniversaryDate(java.time.LocalDate.of(2023, 5, 20));
        when(userService.findProfile("u1")).thenReturn(profile);

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("u1"))
                .andExpect(jsonPath("$.nickname").value("恋恋的小树洞"))
                .andExpect(jsonPath("$.avatarEmoji").value("🌙"))
                .andExpect(jsonPath("$.bio").value("正在练习好好说话"))
                .andExpect(jsonPath("$.anniversaryDate").value("2023-05-20"));
    }

    /** me 未设置纪念日 → anniversaryDate 输出 JSON null（前端据此隐藏「第 N 天」） */
    @Test
    void me_anniversaryUnset_returnsNull() throws Exception {
        TenantContext.set("default", "u1", "USER");
        User profile = new User("u1", "x", "default", "USER");
        when(userService.findProfile("u1")).thenReturn(profile);

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anniversaryDate").value(org.hamcrest.Matchers.nullValue()));
    }

    /** V33：已绑定邮箱 → email + emailVerified=true（本人可见自己的邮箱）。 */
    @Test
    void me_boundEmail_returnsEmailAndVerifiedFlag() throws Exception {
        TenantContext.set("default", "u1", "USER");
        User profile = new User("u1", "x", "default", "USER");
        profile.setEmail("a@b.com");
        profile.setEmailVerifiedAt(java.time.LocalDateTime.now());
        when(userService.findProfile("u1")).thenReturn(profile);

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("a@b.com"))
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    /** V34：未绑定邮箱 → email=null + emailVerified=false（前端据此显示「去绑定」）。 */
    @Test
    void me_noEmail_returnsNullAndFalse() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(userService.findProfile("u1")).thenReturn(new User("u1", "x", "default", "USER"));

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    /** V34 合规：未成年人 → minorMode=true + birthYear 原样返回（前端据此展示提示）。 */
    @Test
    void me_minorUser_returnsMinorModeAndBirthYear() throws Exception {
        TenantContext.set("default", "kid", "USER");
        User profile = new User("kid", "x", "default", "USER");
        profile.setBirthYear(2012);
        profile.setMinorMode(true);
        when(userService.findProfile("kid")).thenReturn(profile);

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minorMode").value(true))
                .andExpect(jsonPath("$.birthYear").value(2012));
    }

    /** V34 兼容：老数据无 birthYear → minorMode=false + birthYear=null（不误伤成年用户）。 */
    @Test
    void me_legacyUserWithoutBirthYear_returnsFalseAndNull() throws Exception {
        TenantContext.set("default", "old", "USER");
        when(userService.findProfile("old")).thenReturn(new User("old", "x", "default", "USER"));

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minorMode").value(false))
                .andExpect(jsonPath("$.birthYear").value(org.hamcrest.Matchers.nullValue()));
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

    /** updateProfile 已登录 + 有更新 → success:true（含 V31 纪念日） */
    @Test
    void updateProfile_loggedIn_updatesFields() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(userService.updateProfile(eq("u1"), eq("新昵称"), eq("❤️"), eq("签名"), eq("2023-05-20")))
                .thenReturn(true);

        mockMvc.perform(put("/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"新昵称\",\"avatarEmoji\":\"❤️\",\"bio\":\"签名\",\"anniversaryDate\":\"2023-05-20\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("资料已更新"));

        verify(userService).updateProfile("u1", "新昵称", "❤️", "签名", "2023-05-20");
    }

    /** 不传 anniversaryDate → 透传 null，既有字段行为不变（V31 回归守卫） */
    @Test
    void updateProfile_omitsAnniversary_passesNull() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(userService.updateProfile(eq("u1"), eq("新昵称"), isNull(), isNull(), isNull()))
                .thenReturn(true);

        mockMvc.perform(put("/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"新昵称\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("资料已更新"));

        verify(userService).updateProfile("u1", "新昵称", null, null, null);
    }

    /** 非法纪念日 → service 抛 BizException → 按既有 Map 契约返回 success:false + 中文提示（V31） */
    @Test
    void updateProfile_invalidAnniversary_returnsError() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(userService.updateProfile(eq("u1"), isNull(), isNull(), isNull(), eq("2023-02-30")))
                .thenThrow(new cn.lwx.lwxaiagent.common.BizException(400,
                        "纪念日格式不正确，应为 yyyy-MM-dd（如 2023-05-20）"));

        mockMvc.perform(put("/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"anniversaryDate\":\"2023-02-30\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("纪念日格式不正确，应为 yyyy-MM-dd（如 2023-05-20）"));
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

    // ================= ④ 进化模块单独同意（V34 合规，docs/07 §5.4） =================

    /** 隐私默认必须是"不同意"：未设置 → evolutionConsent=false。 */
    @Test
    void me_evolutionConsentDefaultsToFalse() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(userService.findProfile("u1")).thenReturn(new User("u1", "x", "default", "USER"));

        mockMvc.perform(get("/auth/me"))
                .andExpect(jsonPath("$.evolutionConsent").value(false));
    }

    /** 已同意 → evolutionConsent=true。 */
    @Test
    void me_evolutionConsentTrue() throws Exception {
        TenantContext.set("default", "u1", "USER");
        User profile = new User("u1", "x", "default", "USER");
        profile.setEvolutionConsent(true);
        when(userService.findProfile("u1")).thenReturn(profile);

        mockMvc.perform(get("/auth/me"))
                .andExpect(jsonPath("$.evolutionConsent").value(true));
    }

    /** 开关：登录用户可同意 → 落库并回 ok。 */
    @Test
    void evolutionConsent_loggedIn_updatesFlag() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(put("/auth/evolution-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consent\":true}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));

        verify(userService).updateEvolutionConsent("u1", true);
    }

    /** 撤回同意同样可写。 */
    @Test
    void evolutionConsent_canBeRevoked() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(put("/auth/evolution-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consent\":false}"))
                .andExpect(jsonPath("$.code").value(200));

        verify(userService).updateEvolutionConsent("u1", false);
    }

    /** ⛔ 缺 consent 字段 → 400：绝不能静默当成 false 写库（用户会以为已同意/已撤回）。 */
    @Test
    void evolutionConsent_missingField_returns400() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(put("/auth/evolution-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("consent 必须为 true 或 false"));

        verify(userService, never()).updateEvolutionConsent(any(), anyBoolean());
    }

    /** 未登录 → 401。 */
    @Test
    void evolutionConsent_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(put("/auth/evolution-consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consent\":true}"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    // ================= ⑤ 首次体验引导（V36，ADR-91） =================

    /** 默认未看过 ⇒ 前端据此显示引导卡。 */
    @Test
    void me_onboardedDefaultsToFalse() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(userService.findProfile("u1")).thenReturn(new User("u1", "x", "default", "USER"));

        mockMvc.perform(get("/auth/me"))
                .andExpect(jsonPath("$.onboarded").value(false));
    }

    /** 已看过 → true。 */
    @Test
    void me_onboardedTrue() throws Exception {
        TenantContext.set("default", "u1", "USER");
        User profile = new User("u1", "x", "default", "USER");
        profile.setOnboarded(true);
        when(userService.findProfile("u1")).thenReturn(profile);

        mockMvc.perform(get("/auth/me"))
                .andExpect(jsonPath("$.onboarded").value(true));
    }

    /** 标记已看过：登录用户可置位，回 ok。 */
    @Test
    void onboarded_loggedIn_marksFlag() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(post("/auth/onboarded"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));

        verify(userService).markOnboarded("u1");
    }

    /** 未登录必须 401（⛔ 这条路径已被加进拦截器白名单，别把"白名单"误解成"免鉴权"）。 */
    @Test
    void onboarded_notLoggedIn_returns401() throws Exception {
        TenantContext.clear();

        mockMvc.perform(post("/auth/onboarded"))
                .andExpect(jsonPath("$.code").value(401));

        verify(userService, never()).markOnboarded(anyString());
    }
}
