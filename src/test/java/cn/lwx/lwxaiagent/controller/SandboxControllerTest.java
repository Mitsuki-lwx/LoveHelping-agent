package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphRunner;
import cn.lwx.lwxaiagent.service.SandboxService;
import cn.lwx.lwxaiagent.service.SandboxTaViewService;
import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * SandboxController 契约测试（2026-09-07 课2 补盲）。
 * <p>重点：越权面（沙盘会话 owner 校验由 service 抛 403/404，controller 不得吞错）、
 * 未登录 500 契约、匿名可访问面（personas）。
 */
@WebMvcTest(SandboxController.class)
class SandboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SandboxService sandboxService;
    @MockBean
    private JwtTokenProvider jwtTokenProvider;
    @MockBean
    private GraphRunner graphRunner;
    @MockBean
    private GuardrailRuleService guardrailRuleService;
    @MockBean
    private cn.lwx.lwxaiagent.service.SandboxTaViewService taViewService;
    @MockBean
    private cn.lwx.lwxaiagent.service.SandboxReviewService reviewService;

    @AfterEach
    void clean() {
        TenantContext.clear();
    }

    @Test
    void create_notLoggedIn_returnsError() throws Exception {
        mockMvc.perform(post("/sandbox/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"REALISTIC\"}"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    void create_loggedIn_returnsSandboxId() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(sandboxService.createSession(eq("u1"), eq("REALISTIC"), any(), any(), any()))
                .thenReturn(Map.of("sandboxId", 42L, "channel", "REALISTIC"));

        mockMvc.perform(post("/sandbox/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"REALISTIC\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sandboxId").value(42));
    }

    @Test
    void list_notLoggedIn_returnsError() throws Exception {
        mockMvc.perform(get("/sandbox/list"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    /** 越权核心面：访问他人沙盘会话 → service 403 必须原样透出（controller 不得吞） */
    @Test
    void get_otherUsersSession_returns403() throws Exception {
        TenantContext.set("default", "u1", "USER");
        doThrow(new BizException(403, "无权访问此沙盘会话"))
                .when(sandboxService).getSession(eq(99L), eq("u1"));

        mockMvc.perform(get("/sandbox/99"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权访问此沙盘会话"));
    }

    @Test
    void get_notExists_returns404() throws Exception {
        TenantContext.set("default", "u1", "USER");
        doThrow(new BizException(404, "沙盘会话不存在"))
                .when(sandboxService).getSession(eq(999L), eq("u1"));

        mockMvc.perform(get("/sandbox/999"))
                .andExpect(jsonPath("$.code").value(404));
    }

    /** personas 是匿名可读面（登录前展示用）——不得要求登录 */
    @Test
    void personas_anonymous_returns200() throws Exception {
        when(sandboxService.listPersonas()).thenReturn(List.of());

        mockMvc.perform(get("/sandbox/personas"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ================= TA 视角推演（2026-09-08 产品闭环 ①） =================

    @Test
    void taView_notLoggedIn_returnsError() throws Exception {
        mockMvc.perform(post("/sandbox/ta-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personaId\":1,\"message\":\"在吗\"}"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    void taView_withoutPersona_returns400() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(post("/sandbox/ta-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"在吗\"}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void taView_success_returnsReplyAndInsight() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(taViewService.taView(eq(1L), any(), eq("我今天没回你消息")))
                .thenReturn(new SandboxTaViewService.TaViewResult("小傲娇", "哼，谁在乎你啊。", "嘴硬，其实在意"));

        mockMvc.perform(post("/sandbox/ta-view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personaId\":1,\"message\":\"我今天没回你消息\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.personaName").value("小傲娇"))
                .andExpect(jsonPath("$.data.reply").value("哼，谁在乎你啊。"))
                .andExpect(jsonPath("$.data.insight").value("嘴硬，其实在意"));
    }

    @Test
    void delete_loggedIn_returnsOk() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(delete("/sandbox/42"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));
    }
}
