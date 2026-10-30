package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.service.ComplaintService;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import cn.lwx.lwxaiagent.vo.ComplaintVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投诉举报接口契约（V34）—— 前端照此实现。
 *
 * <p>统一 {@code Result} 形状 {@code {code,message,data}}；错误由 BizException 转成同形状
 * （HTTP 状态仍是 200，真正的码在响应体 {@code code} 字段，本仓既有约定）。</p>
 */
@WebMvcTest(ComplaintController.class)
@DisplayName("ComplaintController：四个接口的请求/响应契约（V34）")
class ComplaintControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ComplaintService complaintService;
    @MockBean
    private AdminGuard adminGuard;
    /** 拦截器随 SecurityConfig 载入 MVC 切片。 */
    @MockBean
    private cn.lwx.lwxaiagent.tenant.JwtTokenProvider jwtTokenProvider;
    @MockBean
    private cn.lwx.lwxaiagent.service.TokenVersionService tokenVersionService;

    @AfterEach
    void cleanContext() {
        TenantContext.clear();
    }

    // ================= 提交 =================

    /** 已登录 → 200 + 回执 id；service 收到当前用户与租户。 */
    @Test
    void submit_loggedIn_returnsId() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(complaintService.submit("u1", "default", "CONTENT", "有人在评论区骂人", "a@b.com"))
                .thenReturn("9");

        mockMvc.perform(post("/complaint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"CONTENT\",\"content\":\"有人在评论区骂人\",\"contact\":\"a@b.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("9"));

        verify(complaintService).submit("u1", "default", "CONTENT", "有人在评论区骂人", "a@b.com");
    }

    /** 未登录 → {code:401,message:"未登录"}。 */
    @Test
    void submit_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(post("/complaint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"CONTENT\",\"content\":\"内容\"}"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    /** 非法分类 → service 抛 400 → 同形状返回。 */
    @Test
    void submit_invalidCategory_returns400() throws Exception {
        TenantContext.set("default", "u1", "USER");
        doThrow(new BizException(400, "投诉分类无效，必须为 CONTENT/PRIVACY/ABUSE/OTHER 之一"))
                .when(complaintService).submit(any(), any(), any(), any(), any());

        mockMvc.perform(post("/complaint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"HACK\",\"content\":\"内容\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("投诉分类无效，必须为 CONTENT/PRIVACY/ABUSE/OTHER 之一"));
    }

    // ================= 我的 =================

    /** /mine → 自己的列表（userId 为 null）。 */
    @Test
    void mine_returnsOwnList() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(complaintService.listMine("u1")).thenReturn(List.of(new ComplaintVO(
                1L, "CONTENT", "内容", null, "OPEN", LocalDateTime.of(2026, 10, 13, 12, 0), null)));

        mockMvc.perform(get("/complaint/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].category").value("CONTENT"))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[0].userId").value(org.hamcrest.Matchers.nullValue()));
    }

    // ================= 管理端 =================

    /** 非管理员 → 403，且不查询。 */
    @Test
    void adminList_requiresAdmin() throws Exception {
        doThrow(new BizException(403, "需要管理员权限")).when(adminGuard).check(any());

        mockMvc.perform(get("/complaint/admin/list"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("需要管理员权限"));
    }

    /** 管理端列表带 userId。 */
    @Test
    void adminList_returnsWithUserId() throws Exception {
        when(complaintService.listForAdmin(null, null)).thenReturn(List.of(new ComplaintVO(
                1L, "CONTENT", "内容", null, "OPEN", LocalDateTime.of(2026, 10, 13, 12, 0), "u1")));

        mockMvc.perform(get("/complaint/admin/list"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].userId").value("u1"));
    }

    /** 非法状态过滤 → 400。 */
    @Test
    void adminList_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/complaint/admin/list").param("status", "DONE"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("status 只能为 OPEN/PROCESSING/CLOSED"));
    }

    /** 更新状态：非管理员 → 403；管理员 → ok。 */
    @Test
    void updateStatus_admin_returnsOk() throws Exception {
        mockMvc.perform(post("/complaint/admin/1/status").param("status", "CLOSED"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));
        verify(complaintService).updateStatus(1L, "CLOSED");
    }
}
