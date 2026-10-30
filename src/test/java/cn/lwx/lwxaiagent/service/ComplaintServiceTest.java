package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.Complaint;
import cn.lwx.lwxaiagent.mapper.ComplaintMapper;
import cn.lwx.lwxaiagent.support.InMemoryRedis;
import cn.lwx.lwxaiagent.vo.ComplaintVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>投诉举报（V34）：校验 / 限流 / 列表 / 状态</h3>
 *
 * <p>限流用 {@link InMemoryRedis}（真实 INCR + TTL 语义）——只 mock「返回 1」
 * 等于没测"第 6 条被拒"。</p>
 */
@DisplayName("ComplaintService：校验、24 小时限流、列表与状态（V34）")
class ComplaintServiceTest {

    private ComplaintMapper mapper;
    private InMemoryRedis redis;
    private ComplaintService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ComplaintMapper.class);
        redis = new InMemoryRedis();
        service = new ComplaintService(mapper, redis.template());
    }

    // ================= 校验 =================

    /** 非法分类 → 400，且**不落库、不消耗配额**。 */
    @Test
    void invalidCategory_isRejected_andConsumesNothing() {
        assertThatThrownBy(() -> service.submit("u1", "default", "HACK", "内容", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("投诉分类无效");
        assertThatThrownBy(() -> service.submit("u1", "default", null, "内容", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("投诉分类无效");

        verify(mapper, never()).insert(any(Complaint.class));
        assertThat(redis.has("complaint:quota:u1")).as("非法请求不该消耗配额").isFalse();
    }

    /** 正文空/超长 → 400；恰好 1000 字允许（边界含）。 */
    @Test
    void blankOrOverlongContent_isRejected() {
        assertThatThrownBy(() -> service.submit("u1", "default", "OTHER", "  ", null))
                .isInstanceOf(BizException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.submit("u1", "default", "OTHER", "x".repeat(1001), null))
                .isInstanceOf(BizException.class).hasMessageContaining("1000");
        when(mapper.insert(any(Complaint.class))).thenReturn(1);
        assertThat(service.submit("u1", "default", "OTHER", "x".repeat(1000), null)).isNotNull();
    }

    // ================= 落库 =================

    /** 合法提交：状态 OPEN、归属当前用户、回执新 id。 */
    @Test
    void validSubmit_persistsOpenComplaint() {
        when(mapper.insert(any(Complaint.class))).thenAnswer(inv -> {
            inv.getArgument(0, Complaint.class).setId(7L);
            return 1;
        });

        String id = service.submit("u1", "default", "CONTENT", "有人在评论区骂人", "a@b.com");

        assertThat(id).isEqualTo("7");
        ArgumentCaptor<Complaint> captor = ArgumentCaptor.forClass(Complaint.class);
        verify(mapper).insert(captor.capture());
        Complaint saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getUserId()).isEqualTo("u1");
        assertThat(saved.getCategory()).isEqualTo("CONTENT");
        assertThat(saved.getContent()).isEqualTo("有人在评论区骂人");
        assertThat(saved.getContact()).isEqualTo("a@b.com");
        assertThat(saved.getTenantId()).isEqualTo("default");
    }

    // ================= 限流 =================

    /** 同一用户 24 小时内第 6 条 → 429（前 5 条正常落库）。 */
    @Test
    void sixthWithinDay_isRejected429() {
        when(mapper.insert(any(Complaint.class))).thenReturn(1);
        for (int i = 0; i < 5; i++) {
            service.submit("u1", "default", "OTHER", "第 " + i + " 条", null);
        }

        BizException ex = assertThrows(BizException.class,
                () -> service.submit("u1", "default", "OTHER", "第 6 条", null));
        assertThat(ex.getCode()).isEqualTo(429);
        assertThat(ex.getMessage()).contains("提交过于频繁");
        verify(mapper, times(5)).insert(any(Complaint.class));
    }

    /** 配额键是 24 小时窗口。 */
    @Test
    void quotaKeyHas24hTtl() {
        when(mapper.insert(any(Complaint.class))).thenReturn(1);
        service.submit("u1", "default", "OTHER", "内容", null);
        assertThat(redis.ttl("complaint:quota:u1")).isEqualTo(Duration.ofHours(24));
    }

    /** 限流按用户隔离：他人配额不影响我。 */
    @Test
    void quotaIsPerUser() {
        when(mapper.insert(any(Complaint.class))).thenReturn(1);
        for (int i = 0; i < 5; i++) {
            service.submit("u1", "default", "OTHER", "内容", null);
        }
        assertThat(service.submit("u2", "default", "OTHER", "内容", null)).isNotNull();
    }

    // ================= 列表 =================

    /** /mine：带 userId=null（自己的记录不需要再标归属）。 */
    @Test
    void listMine_mapsWithoutUserId() {
        when(mapper.selectList(any())).thenReturn(List.of(complaint(1L, "u1", "OPEN")));
        List<ComplaintVO> list = service.listMine("u1");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(1L);
        assertThat(list.get(0).status()).isEqualTo("OPEN");
        assertNull(list.get(0).userId());
    }

    /** 管理端列表：带 userId（跨用户需要知道是谁提交的）。 */
    @Test
    void listForAdmin_mapsWithUserId() {
        when(mapper.selectList(any())).thenReturn(List.of(complaint(1L, "u1", "OPEN")));
        List<ComplaintVO> list = service.listForAdmin("OPEN", 50);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).userId()).isEqualTo("u1");
    }

    /** limit 归一化：缺省 50、下限 1、上限 200（防一次拉爆）。 */
    @Test
    void normalizeLimit_clampsToBounds() {
        assertThat(ComplaintService.normalizeLimit(null)).isEqualTo(50);
        assertThat(ComplaintService.normalizeLimit(0)).isEqualTo(50);
        assertThat(ComplaintService.normalizeLimit(-3)).isEqualTo(50);
        assertThat(ComplaintService.normalizeLimit(30)).isEqualTo(30);
        assertThat(ComplaintService.normalizeLimit(9999)).isEqualTo(200);
    }

    // ================= 状态 =================

    /** 非法状态 → 400，不落库。 */
    @Test
    void updateStatus_invalid_isRejected() {
        assertThatThrownBy(() -> service.updateStatus(1L, "DONE"))
                .isInstanceOf(BizException.class).hasMessageContaining("OPEN/PROCESSING/CLOSED");
        verify(mapper, never()).updateById(any(Complaint.class));
    }

    /** 不存在 → 404。 */
    @Test
    void updateStatus_missing_is404() {
        when(mapper.selectById(1L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.updateStatus(1L, "CLOSED"));
        assertThat(ex.getCode()).isEqualTo(404);
    }

    /** 合法状态 → 落库。 */
    @Test
    void updateStatus_persists() {
        Complaint c = complaint(1L, "u1", "OPEN");
        when(mapper.selectById(1L)).thenReturn(c);
        service.updateStatus(1L, "CLOSED");
        assertThat(c.getStatus()).isEqualTo("CLOSED");
        verify(mapper).updateById(c);
    }

    private static Complaint complaint(Long id, String userId, String status) {
        Complaint c = new Complaint();
        c.setId(id);
        c.setUserId(userId);
        c.setCategory("CONTENT");
        c.setContent("内容");
        c.setStatus(status);
        c.setCreatedAt(LocalDateTime.of(2026, 10, 13, 12, 0));
        return c;
    }
}
