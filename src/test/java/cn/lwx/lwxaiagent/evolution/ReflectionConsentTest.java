package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerBudget;
import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * <h3>进化模块「单独同意」（V34 合规，docs/07 §5.4）</h3>
 *
 * <p>为什么必须有这一层：{@code ReflectionScheduler} 原本**无条件**把用户对话提炼成技能，
 * 没有同意开关时"放开审核"= 把<b>未同意的用户数据</b>放进知识库。</p>
 *
 * <p>这里 mock 掉 {@link SkillReflector}，只断言"有没有被调用"——即
 * consent=0 的会话<b>绝不产生技能</b>，consent=1 的才产生。</p>
 */
@DisplayName("ReflectionScheduler：进化模块单独同意（V34）")
class ReflectionConsentTest {

    private JdbcTemplate jdbcTemplate;
    private SkillReflector reflector;
    private SchedulerBudget budget;
    private ReflectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        reflector = mock(SkillReflector.class);
        budget = mock(SchedulerBudget.class);
        EvolutionProperties props = new EvolutionProperties();   // enabled=true 默认
        scheduler = new ReflectionScheduler(props, budget, new SchedulerProperties());
        ReflectionTestUtils.setField(scheduler, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(scheduler, "skillReflector", reflector);
    }

    private void stubBudget(int allowance) {
        when(budget.permitted("reflect")).thenReturn(true);
        when(budget.backoffAllowsRun("reflect")).thenReturn(true);
        when(budget.allowance(eq("reflect"), anyInt())).thenReturn(allowance);
        when(budget.maxRunMs()).thenReturn(60_000L);
    }

    /** 候选会话由被测类之外给定（SQL 过滤那层另有覆盖），这里只验"同意门"。 */
    private ReflectionScheduler withSessions(String... sessionIds) {
        ReflectionScheduler spy = spy(scheduler);
        doReturn(List.of(sessionIds)).when(spy).findReadySessions();
        return spy;
    }

    /** consent 查询桩：按会话 id 返回 1/0；返回 null 表示查不到归属。 */
    private void stubConsent(java.util.function.Function<String, List<Integer>> bySession) {
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), any())).thenAnswer(inv -> {
            Object raw = inv.getArguments()[2];
            String sid = raw instanceof Object[] arr ? String.valueOf(arr[0]) : String.valueOf(raw);
            return bySession.apply(sid);
        });
    }

    /** consent=1 → 调用 reflect（技能才会产生）。 */
    @Test
    void consentedSession_isReflected() {
        stubBudget(1);
        stubConsent(sid -> List.of(1));
        ReflectionScheduler spy = withSessions("s1");

        spy.scanAndReflect();

        verify(reflector).reflect("s1", "default");
        verify(budget).consume("reflect", 1);
    }

    /** ⛔ consent=0 → 绝不调用 reflect，也不消耗预算（不产生任何技能）。 */
    @Test
    void nonConsentedSession_producesNoSkill() {
        stubBudget(1);
        stubConsent(sid -> List.of(0));
        ReflectionScheduler spy = withSessions("s1");

        spy.scanAndReflect();

        verifyNoInteractions(reflector);
        verify(budget, never()).consume(anyString(), anyInt());
    }

    /** 混合：只有同意的那个被反思。 */
    @Test
    void mixedSessions_onlyConsentedIsReflected() {
        stubBudget(2);
        stubConsent(sid -> "s1".equals(sid) ? List.of(1) : List.of(0));
        ReflectionScheduler spy = withSessions("s1", "s2");

        spy.scanAndReflect();

        verify(reflector).reflect("s1", "default");
        verify(reflector, never()).reflect(eq("s2"), anyString());
    }

    /** ⛔ 查不到归属的会话一律跳过（宁可少做，不可越界）。 */
    @Test
    void unownedSession_isSkipped() {
        stubBudget(1);
        stubConsent(sid -> List.of());   // 归属查询无结果
        ReflectionScheduler spy = withSessions("orphan");

        spy.scanAndReflect();

        verifyNoInteractions(reflector);
    }

    /** 同意门判定本身：1=同意，0/空=不同意。 */
    @Test
    void hasEvolutionConsent_readsFlagFromOwner() {
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), any())).thenReturn(List.of(1));
        org.assertj.core.api.Assertions.assertThat(scheduler.hasEvolutionConsent("s1")).isTrue();

        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), any())).thenReturn(List.of(0));
        org.assertj.core.api.Assertions.assertThat(scheduler.hasEvolutionConsent("s1")).isFalse();

        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), any())).thenReturn(List.of());
        org.assertj.core.api.Assertions.assertThat(scheduler.hasEvolutionConsent("s1")).isFalse();
    }
}
