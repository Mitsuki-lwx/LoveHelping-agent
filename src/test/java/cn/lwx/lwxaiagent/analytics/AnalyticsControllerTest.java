package cn.lwx.lwxaiagent.analytics;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>AnalyticsController 端点测试</h3>
 *
 * <p>钉住两条线：</p>
 * <ul>
 *   <li>非管理员（无 X-Admin-Key、JWT role 非 ADMIN）→ 403，且不触达 service（fail-closed）；</li>
 *   <li>管理员 → Result.ok 包裹的总览，days 原样透传给 service。</li>
 * </ul>
 */
@DisplayName("AnalyticsController：管理端看板端点")
class AnalyticsControllerTest {

    private AnalyticsService analyticsService;
    private AdminGuard adminGuard;
    private AnalyticsController controller;

    @BeforeEach
    void setUp() {
        analyticsService = mock(AnalyticsService.class);
        adminGuard = mock(AdminGuard.class);
        controller = new AnalyticsController(analyticsService, adminGuard);
    }

    private static AnalyticsOverviewVO sample(int days) {
        return new AnalyticsOverviewVO(
                days, LocalDateTime.of(2026, 10, 14, 20, 0), 2400,
                new AnalyticsOverviewVO.DataQualityVO(2642, 2400, 0.908, true),
                List.of(new AnalyticsOverviewVO.DailyPointVO("2026-10-14", 3, 1, 12)),
                new AnalyticsOverviewVO.RetentionVO(50, 0.42, 0.18),
                List.of(new AnalyticsOverviewVO.FunnelStepVO("注册", 50)),
                List.of(new AnalyticsOverviewVO.FeatureUsageVO("聊天会话", 120)));
    }

    @Test
    void overview_requiresAdminGuard() {
        doThrow(new BizException(403, "需要管理员权限")).when(adminGuard).check(any());
        assertThatThrownBy(() -> controller.overview(new MockHttpServletRequest(), 30))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需要管理员权限");
        verify(analyticsService, never()).overview(30);
    }

    @Test
    void overview_returnsResultWrappedOverview() {
        when(analyticsService.overview(30)).thenReturn(sample(30));

        Result<AnalyticsOverviewVO> r = controller.overview(new MockHttpServletRequest(), 30);

        assertThat(r.code()).isEqualTo(200);
        assertThat(r.data().days()).isEqualTo(30);
        assertThat(r.data().excludedTestUsers()).isEqualTo(2400L);
        verify(adminGuard).check(any());
        verify(analyticsService).overview(30);
    }

    @Test
    void overview_passesDaysThrough() {
        when(analyticsService.overview(7)).thenReturn(sample(7));

        controller.overview(new MockHttpServletRequest(), 7);

        verify(analyticsService).overview(7);
    }
}
