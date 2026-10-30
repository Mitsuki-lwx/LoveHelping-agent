package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.entity.RelationshipProfile;
import cn.lwx.lwxaiagent.mapper.RelationshipProfileMapper;
import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 关系档案接口契约测试（GET/PUT /profile/relationship）。
 *
 * <p>这段数据会经 MemoryStore 拼进模型提示词，所以两条底线必须钉死：
 * ① stage 非法值**报错且不落库**（verify 证明没调 insert/updateById）；
 * ② 用户没填过时 GET 返回空值而不是 404。</p>
 */
@WebMvcTest(RelationshipController.class)
class RelationshipControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RelationshipProfileMapper profileMapper;
    @MockBean
    private JwtTokenProvider jwtTokenProvider;
    /** V33：TenantInterceptor 依赖令牌版本服务（拦截器随 SecurityConfig 载入 MVC 切片）。 */
    @MockBean
    private cn.lwx.lwxaiagent.service.TokenVersionService tokenVersionService;

    @AfterEach
    void clean() {
        TenantContext.clear();
    }

    // ==================== GET ====================

    @Test
    @DisplayName("未登录 → 401")
    void get_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(get("/profile/relationship"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));
    }

    @Test
    @DisplayName("行不存在 → 200 且 data 三个字段全 null（不是 404）")
    void get_noRow_returnsEmptyNotNull() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(profileMapper.selectById(anyString())).thenReturn(null);

        mockMvc.perform(get("/profile/relationship"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stage").doesNotExist())
                .andExpect(jsonPath("$.data.partnerAlias").doesNotExist())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("已有档案 → 返回 stage / partnerAlias / updatedAt")
    void get_existingRow_returnsValues() throws Exception {
        TenantContext.set("default", "u1", "USER");
        RelationshipProfile p = new RelationshipProfile();
        p.setUserId("u1");
        p.setStage("恋爱中");
        p.setPartnerAlias("宝宝");
        p.setLastUpdatedAt(java.time.LocalDateTime.of(2026, 10, 7, 12, 0));
        when(profileMapper.selectById("u1")).thenReturn(p);

        mockMvc.perform(get("/profile/relationship"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stage").value("恋爱中"))
                .andExpect(jsonPath("$.data.partnerAlias").value("宝宝"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-10-07T12:00"));
    }

    // ==================== PUT ====================

    @Test
    @DisplayName("未登录 → 401")
    void put_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(put("/profile/relationship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"恋爱中\"}"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("行不存在 → insert（upsert），返回已更新")
    void put_noRow_inserts() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(profileMapper.selectById("u1")).thenReturn(null);

        mockMvc.perform(put("/profile/relationship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"恋爱中\",\"partnerAlias\":\"宝宝\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("已更新"))
                .andExpect(jsonPath("$.data.stage").value("恋爱中"))
                .andExpect(jsonPath("$.data.partnerAlias").value("宝宝"));

        ArgumentCaptor<RelationshipProfile> captor = ArgumentCaptor.forClass(RelationshipProfile.class);
        verify(profileMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("u1");
        assertThat(captor.getValue().getStage()).isEqualTo("恋爱中");
        assertThat(captor.getValue().getPartnerAlias()).isEqualTo("宝宝");
        verify(profileMapper, never()).updateById(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
    }

    @Test
    @DisplayName("行已存在 → updateById，且只覆盖传入字段")
    void put_existingRow_updates() throws Exception {
        TenantContext.set("default", "u1", "USER");
        RelationshipProfile p = new RelationshipProfile();
        p.setUserId("u1");
        p.setStage("单身");
        p.setPartnerAlias("老王");
        when(profileMapper.selectById("u1")).thenReturn(p);

        mockMvc.perform(put("/profile/relationship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"已婚\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stage").value("已婚"))
                .andExpect(jsonPath("$.data.partnerAlias").value("老王"));

        verify(profileMapper).updateById(p);
        assertThat(p.getStage()).isEqualTo("已婚");
        assertThat(p.getPartnerAlias()).as("未传的字段要保持原值").isEqualTo("老王");
        verify(profileMapper, never()).insert(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
    }

    @Test
    @DisplayName("⛔ 非法 stage → 400 且**不落库**（insert/updateById 一次都没调）")
    void put_invalidStage_rejectedWithoutWrite() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(put("/profile/relationship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"热恋中\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "stage 非法，只能是：单身 / 暧昧中 / 恋爱中 / 异地恋 / 已婚 / 其他"));

        verify(profileMapper, never()).insert(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
        verify(profileMapper, never()).updateById(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
    }

    @Test
    @DisplayName("partnerAlias 超长 → 400 且不落库")
    void put_aliasTooLong_rejectedWithoutWrite() throws Exception {
        TenantContext.set("default", "u1", "USER");

        mockMvc.perform(put("/profile/relationship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerAlias\":\"" + "宝".repeat(33) + "\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("partnerAlias 过长（最多 32 字符）"));

        verify(profileMapper, never()).insert(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
        verify(profileMapper, never()).updateById(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
    }

    @Test
    @DisplayName("partnerAlias 两侧空白被 trim 后落库")
    void put_aliasTrimmed() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(profileMapper.selectById("u1")).thenReturn(null);

        mockMvc.perform(put("/profile/relationship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partnerAlias\":\"  宝宝  \"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.partnerAlias").value("宝宝"));

        ArgumentCaptor<RelationshipProfile> captor = ArgumentCaptor.forClass(RelationshipProfile.class);
        verify(profileMapper).insert(captor.capture());
        assertThat(captor.getValue().getPartnerAlias()).isEqualTo("宝宝");
    }

    @Test
    @DisplayName("两个字段都为空/空白 → 不写库，message 提示没有需要更新的字段")
    void put_noFields_doesNotWrite() throws Exception {
        TenantContext.set("default", "u1", "USER");
        when(profileMapper.selectById("u1")).thenReturn(null);

        mockMvc.perform(put("/profile/relationship")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"  \",\"partnerAlias\":\"\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("没有需要更新的字段"));

        verify(profileMapper, never()).insert(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
        verify(profileMapper, never()).updateById(any(cn.lwx.lwxaiagent.entity.RelationshipProfile.class));
    }
}
