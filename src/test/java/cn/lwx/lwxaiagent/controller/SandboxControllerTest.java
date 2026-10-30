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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
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
    /** V33：TenantInterceptor 依赖令牌版本服务（拦截器随 SecurityConfig 载入 MVC 切片）。 */
    @MockBean
    private cn.lwx.lwxaiagent.service.TokenVersionService tokenVersionService;
    @MockBean
    private cn.lwx.lwxaiagent.infrastructure.orchestration.ChatEntry chatEntry;
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

    // ================= 沙盘带图（mediaIds 逗号分隔，ADR-11） =================

    @Test
    void chat_withMediaIds_forwardsParsedIdsToEntry() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(guardrailRuleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        when(sandboxService.getSession(eq(42L), eq("u1"))).thenReturn(null);
        when(chatEntry.sandbox(anyString(), anyLong(), anyString(), anyList())).thenReturn(Flux.just("回复"));

        var pending = mockMvc.perform(get("/sandbox/chat")
                        .param("sandboxId", "42")
                        .param("message", "看看这张照片")
                        .param("mediaIds", "7, 8"))
                .andReturn();
        pending.getAsyncResult(5000);
        var body = mockMvc.perform(asyncDispatch(pending)).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("回复"), body);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatEntry).sandbox(eq("看看这张照片"), eq(42L), eq("u1"), captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(7L, 8L), captor.getValue());
    }

    /** 回归守卫：不带 mediaIds 时行为与改造前完全一致（空列表进 entry，会话/护栏照旧）。 */
    @Test
    void chat_withoutMediaIds_forwardsEmptyList() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(guardrailRuleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        when(sandboxService.getSession(eq(42L), eq("u1"))).thenReturn(null);
        when(chatEntry.sandbox(anyString(), anyLong(), anyString(), anyList())).thenReturn(Flux.just("普通回复"));

        // ⛔ 本测试**只做接线断言**，不走 MockMvc 的 async 派发。
        //
        //   2026-10-14 实测：带上 `getAsyncResult + asyncDispatch` 时，这个用例在 8 轮全量里红了 2 次，
        //   两种症状（`Failed to send [...]`、`ConcurrentModificationException` in
        //   `MockHttpServletResponse` 的 header map）都落在 **MockMvc 自己的异步机制**里 ——
        //   那是**测试装置产物**（真 Tomcat 没有这个 map），不是产品缺陷。
        //   而"SSE 正文确实转发给了客户端"这条，主链路的 `AiControllerSseTest` 已覆盖
        //   （它用 standaloneSetup + 显式转换器，走的是稳定的那条路）。
        //   ⇒ 这里保留本测试**独有**的价值：空 mediaIds 透传、会话归属校验、护栏调用。
        mockMvc.perform(get("/sandbox/chat")
                .param("sandboxId", "42")
                .param("message", "在吗"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatEntry).sandbox(eq("在吗"), eq(42L), eq("u1"), captor.capture());
        org.junit.jupiter.api.Assertions.assertTrue(captor.getValue().isEmpty());
        verify(sandboxService).getSession(eq(42L), eq("u1"));
    }

    /** 安全回归：L3 输入护栏在入口同步阻断——带图也不得绕过（编排图根本不该被调用）。 */
    @Test
    void chat_l3Guardrail_stillBlocksBeforeEntryEvenWithMediaIds() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(guardrailRuleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(3, "self_harm"));

        mockMvc.perform(get("/sandbox/chat")
                        .param("sandboxId", "42")
                        .param("message", "我真的撑不下去了")
                        .param("mediaIds", "7"))
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("400-161-9995")));

        verifyNoInteractions(chatEntry);
    }
}
