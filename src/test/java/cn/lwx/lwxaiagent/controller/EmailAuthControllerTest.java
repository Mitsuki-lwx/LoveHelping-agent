package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.service.EmailRecoveryService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱绑定 / 找回密码接口契约（V33）—— 前端照此实现。
 *
 * <p>统一 {@code Result} 形状 {@code {code,message,data}}；错误由 BizException 转成同形状。</p>
 */
@WebMvcTest(EmailAuthController.class)
@DisplayName("EmailAuthController：四个接口的请求/响应契约")
class EmailAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmailRecoveryService emailRecoveryService;
    @MockBean
    private cn.lwx.lwxaiagent.tenant.JwtTokenProvider jwtTokenProvider;
    @MockBean
    private cn.lwx.lwxaiagent.service.TokenVersionService tokenVersionService;

    @AfterEach
    void cleanContext() {
        TenantContext.clear();
    }

    // ================= ① 绑定：发码 =================

    /** 已登录 → 200 + 固定提示，service 收到当前用户与邮箱。 */
    @Test
    void sendBindCode_loggedIn_returnsSuccess() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(post("/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("验证码已发送，请查收邮箱（5 分钟内有效）"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        verify(emailRecoveryService).sendBindCode("u1", "a@b.com");
    }

    /** 未登录（无 TenantContext）→ 401 契约。
     *  <p>⚠️ 前端注意：BizException 走 {@code GlobalExceptionHandler}，HTTP 状态仍是 <b>200</b>，
     *  真正的错误码在响应体的 {@code code} 字段（本仓既有约定，与 /auth/profile 一致）。</p> */
    @Test
    void sendBindCode_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(post("/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    /** 邮箱被占用 → service 抛 BizException → {code:400,message}。 */
    @Test
    void sendBindCode_emailTaken_returns400() throws Exception {
        TenantContext.set("default", "u1", "USER");
        doThrow(new BizException(400, "该邮箱已被其他账号绑定"))
                .when(emailRecoveryService).sendBindCode("u1", "a@b.com");

        mockMvc.perform(post("/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("该邮箱已被其他账号绑定"));
    }

    // ================= ② 绑定：校验 =================

    /** 绑定成功 → data 里回带 email + emailVerified=true。 */
    @Test
    void bindEmail_success_returnsBoundEmail() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(emailRecoveryService.bindEmail("u1", "a@b.com", "123456")).thenReturn("a@b.com");

        mockMvc.perform(post("/auth/email/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("邮箱绑定成功"))
                .andExpect(jsonPath("$.data.email").value("a@b.com"))
                .andExpect(jsonPath("$.data.emailVerified").value(true));
    }

    /** 验证码错误 → 400 + 统一文案。 */
    @Test
    void bindEmail_wrongCode_returns400() throws Exception {
        TenantContext.set("default", "u1", "USER");
        doThrow(new BizException(400, "验证码不正确或已失效，请重新获取"))
                .when(emailRecoveryService).bindEmail("u1", "a@b.com", "000000");

        mockMvc.perform(post("/auth/email/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"code\":\"000000\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("验证码不正确或已失效，请重新获取"));
    }

    // ================= ③ 重置：发码（防枚举） =================

    /** 匿名可访问；响应文案恒定（账号是否存在都一样）。 */
    @Test
    void sendResetCode_anonymous_returnsConstantMessage() throws Exception {
        mockMvc.perform(post("/auth/password/reset/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value(EmailAuthController.RESET_CODE_MESSAGE));

        verify(emailRecoveryService).sendResetCode("ghost");
    }

    /** 存在账号与不存在账号的响应体**逐字节一致**（防账号枚举）。 */
    @Test
    void sendResetCode_existingAndUnknownAccounts_areIndistinguishable() throws Exception {
        String unknown = mockMvc.perform(post("/auth/password/reset/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\"}"))
                .andReturn().getResponse().getContentAsString();
        String existing = mockMvc.perform(post("/auth/password/reset/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"realuser\"}"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(unknown).isEqualTo(existing);
    }

    /** 空用户名 → 400（参数错误，与账号是否存在无关）。 */
    @Test
    void sendResetCode_blankUsername_returns400() throws Exception {
        mockMvc.perform(post("/auth/password/reset/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    // ================= ④ 重置：提交 =================

    /** 成功 → 固定提示。 */
    @Test
    void resetPassword_success_returnsMessage() throws Exception {
        mockMvc.perform(post("/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"code\":\"123456\",\"newPassword\":\"NewPassw0rd!9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("密码已重置，请使用新密码登录"));

        verify(emailRecoveryService).resetPassword("u1", "123456", "NewPassw0rd!9");
    }

    /** 缺字段 → 400 明确提示（不是 500）。 */
    @Test
    void resetPassword_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"code\":\"\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("验证码不能为空"));
    }

    /** 新密码过短 → service 抛 400 → 原样返回。 */
    @Test
    void resetPassword_tooShortPassword_returns400() throws Exception {
        doThrow(new BizException(400, "新密码长度至少 8 位"))
                .when(emailRecoveryService).resetPassword(eq("u1"), eq("123456"), eq("short"));

        mockMvc.perform(post("/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u1\",\"code\":\"123456\",\"newPassword\":\"short\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("新密码长度至少 8 位"));
    }
}
