package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import cn.lwx.lwxaiagent.evolution.SkillIngestor;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import cn.lwx.lwxaiagent.service.EvolutionService;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.vo.SkillVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>技能审核列表 + 批量驳回（V34 审核闭环）</h3>
 *
 * <p>钉住三条线：</p>
 * <ul>
 *   <li><b>必须带 limit</b>：12k+ 条 PENDING，列表与批量驳回都要被夹到上限；</li>
 *   <li><b>批量驳回只碰 PENDING 低分</b>：候选 id 只来自 PENDING 查询，
 *       且 UPDATE 分批（每批 ≤500），不许一次大事务；</li>
 *   <li><b>两个端点都要 adminGuard</b>：非管理员一律 403，且不触达 mapper。</li>
 * </ul>
 */
@DisplayName("VoteController：技能审核列表与批量驳回（V34）")
class VoteControllerSkillsTest {

    private EvolutionSkillMapper skillMapper;
    private AdminGuard adminGuard;
    private VoteController controller;

    @BeforeEach
    void setUp() {
        skillMapper = mock(EvolutionSkillMapper.class);
        adminGuard = mock(AdminGuard.class);
        controller = new VoteController(mock(EvolutionService.class), skillMapper,
                mock(SkillIngestor.class), adminGuard);
    }

    private static EvolutionSkill skill(Long id, String status, Integer score) {
        EvolutionSkill s = new EvolutionSkill("default", "名称" + id, "描述", "正文",
                "sess-" + id, score);
        s.setId(id);
        s.setAuditStatus(status);
        return s;
    }

    // ================= 列表 =================

    /** 缺省 status=PENDING、limit=50。 */
    @Test
    void listSkills_defaultsToPendingWithLimit50() {
        when(skillMapper.findByAuditStatus("PENDING", 50))
                .thenReturn(List.of(skill(1L, "PENDING", 3)));

        Result<List<SkillVO>> r = controller.listSkills(null, null, new MockHttpServletRequest());

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).hasSize(1);
        SkillVO vo = r.data().get(0);
        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.auditStatus()).isEqualTo("PENDING");
        assertThat(vo.qualityScore()).isEqualTo(3);
        assertThat(vo.sourceSessionId()).isEqualTo("sess-1");
        verify(skillMapper).findByAuditStatus("PENDING", 50);
    }

    /** limit 上限 200：传 9999 也只查 200 条。 */
    @Test
    void listSkills_clampsLimitTo200() {
        when(skillMapper.findByAuditStatus(eq("PENDING"), anyInt())).thenReturn(List.of());

        controller.listSkills("PENDING", 9999, new MockHttpServletRequest());

        verify(skillMapper).findByAuditStatus("PENDING", 200);
        verify(skillMapper, never()).findByAuditStatus(any(), eq(9999));
    }

    /** 非法 status → 400。 */
    @Test
    void listSkills_invalidStatus_isRejected400() {
        assertThatThrownBy(() -> controller.listSkills("BOGUS", 50, new MockHttpServletRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PENDING/APPROVED/REJECTED");
        verify(skillMapper, never()).findByAuditStatus(any(), anyInt());
    }

    /** 非管理员 → 403，且不查询（fail-closed）。 */
    @Test
    void listSkills_requiresAdmin() {
        doThrow(new BizException(403, "需要管理员权限")).when(adminGuard).check(any());
        assertThatThrownBy(() -> controller.listSkills("PENDING", 50, new MockHttpServletRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需要管理员权限");
        verify(skillMapper, never()).findByAuditStatus(any(), anyInt());
    }

    // ================= 批量驳回 =================

    /** 分批更新：1200 条 → 500/500/200 三批，返回总数。 */
    @Test
    void bulkReject_batchesAt500_andReturnsCount() {
        List<Long> ids = LongStream.rangeClosed(1, 1200).boxed().toList();
        when(skillMapper.findPendingIdsByMaxScore(5, 2000)).thenReturn(ids);
        when(skillMapper.bulkRejectByIds(anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        Result<Integer> r = controller.bulkReject(5, 2000, new MockHttpServletRequest());

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data()).isEqualTo(1200);
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(skillMapper, times(3)).bulkRejectByIds(captor.capture());
        List<List<Long>> batches = captor.getAllValues();
        assertThat(batches.get(0)).hasSize(500);
        assertThat(batches.get(1)).hasSize(500);
        assertThat(batches.get(2)).hasSize(200);
        // 批次覆盖且仅覆盖候选 id（不重不漏）
        assertThat(batches.stream().flatMap(List::stream).distinct().count()).isEqualTo(1200);
    }

    /** limit 上限 5000：传 99999 也只取 5000。 */
    @Test
    void bulkReject_clampsLimitTo5000() {
        when(skillMapper.findPendingIdsByMaxScore(anyInt(), anyInt())).thenReturn(List.of());

        controller.bulkReject(5, 99999, new MockHttpServletRequest());

        verify(skillMapper).findPendingIdsByMaxScore(5, 5000);
    }

    /** 没有候选 → 0 条、不发任何 UPDATE。 */
    @Test
    void bulkReject_noCandidates_returnsZero() {
        when(skillMapper.findPendingIdsByMaxScore(5, 2000)).thenReturn(List.of());
        Result<Integer> r = controller.bulkReject(5, 2000, new MockHttpServletRequest());
        assertThat(r.data()).isZero();
        verify(skillMapper, never()).bulkRejectByIds(anyList());
    }

    /**
     * ⛔ 已 APPROVED 的绝不能被驳回：更新只针对 PENDING 查询返回的 id，
     * 传入的 id 集合里**不得**出现任何非候选（如已审核通过）的 id。
     */
    @Test
    void bulkReject_neverTouchesApprovedIds() {
        // 候选只含 PENDING 的 1、2；99 是已 APPROVED 的技能，不在候选里
        when(skillMapper.findPendingIdsByMaxScore(5, 2000)).thenReturn(List.of(1L, 2L));
        when(skillMapper.bulkRejectByIds(anyList())).thenReturn(2);

        controller.bulkReject(5, 2000, new MockHttpServletRequest());

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(skillMapper).bulkRejectByIds(captor.capture());
        assertThat(captor.getValue()).containsExactly(1L, 2L).doesNotContain(99L);
    }

    /** 非管理员 → 403，且不取候选、不更新。 */
    @Test
    void bulkReject_requiresAdmin() {
        doThrow(new BizException(403, "需要管理员权限")).when(adminGuard).check(any());
        assertThatThrownBy(() -> controller.bulkReject(5, 2000, new MockHttpServletRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需要管理员权限");
        verify(skillMapper, never()).findPendingIdsByMaxScore(anyInt(), anyInt());
        verify(skillMapper, never()).bulkRejectByIds(anyList());
    }
}
