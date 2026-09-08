package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.memory.MemoryStore;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * MemoryFactsController 契约测试（2026-09-07 课2 补盲）。
 * <p>记忆档案是用户隐私面：401 未登录契约、400 参数校验、403 越权（他人记忆）
 * ——三条错误码路径全部钉死。
 */
@WebMvcTest(MemoryFactsController.class)
class MemoryFactsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemoryStore memoryStore;
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @AfterEach
    void clean() {
        TenantContext.clear();
    }

    // ================= ⑤ 手动添加（2026-09-08） =================

    @Test
    void addFact_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(post("/memory/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我希望被直球安慰\"}"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void addFact_success_returnsAdded() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(memoryStore.addUserFact(eq("u1"), eq("偏好"), eq("我希望被直球安慰"))).thenReturn(88L);

        mockMvc.perform(post("/memory/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我希望被直球安慰\",\"category\":\"偏好\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("added"));
    }

    @Test
    void addFact_duplicate_returns400() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(memoryStore.addUserFact(eq("u1"), eq("偏好"), eq("重复内容"))).thenReturn(null);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/memory/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"重复内容\",\"category\":\"偏好\"}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listFacts_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(get("/memory/facts"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    void listFacts_loggedIn_returnsFacts() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(memoryStore.listFacts("u1")).thenReturn(List.of());

        mockMvc.perform(get("/memory/facts"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void updateFact_blankContent_returns400() throws Exception {
        mockMvc.perform(put("/memory/facts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  \"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("content 不能为空"));
    }

    @Test
    void updateFact_tooLong_returns400() throws Exception {
        String longContent = "x".repeat(501);
        mockMvc.perform(put("/memory/facts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + longContent + "\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("content 过长（最多 500 字）"));
    }

    /** 越权面：更新他人记忆 → memoryStore 返回 false → 403 */
    @Test
    void updateFact_notOwner_returns403() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(memoryStore.updateFact(eq("u1"), eq(9L), anyString())).thenReturn(false);

        mockMvc.perform(put("/memory/facts/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"纠正内容\"}"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void updateFact_success_returnsUpdated() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(memoryStore.updateFact(eq("u1"), eq(1L), anyString())).thenReturn(true);

        mockMvc.perform(put("/memory/facts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"纠正内容\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("updated"));
    }

    @Test
    void deleteFact_notOwner_returns403() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(memoryStore.deleteFact(eq("u1"), eq(2L))).thenReturn(false);

        mockMvc.perform(delete("/memory/facts/2"))
                .andExpect(jsonPath("$.code").value(403));
    }
}
