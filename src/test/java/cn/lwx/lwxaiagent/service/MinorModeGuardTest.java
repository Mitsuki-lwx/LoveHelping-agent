package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import cn.lwx.lwxaiagent.support.InMemoryRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * <h3>未成年人模式（V34 合规）：宵禁 / 每日上限 / 放行原则</h3>
 *
 * <p>时间用<b>固定时间点</b>注入（{@code enforce(userId, now)}），不依赖真实钟表——
 * 否则"宵禁命中"这类断言在白天跑就是假绿。</p>
 *
 * <p>计数用 {@link InMemoryRedis}（带真实 INCR/TTL 语义），不连真 Redis；
 * 但"Redis 不可用必须放行"那一条<b>必须用会抛异常的替身</b>来证明，不能靠想象。</p>
 */
@DisplayName("MinorModeGuard：宵禁与每日上限（V34 合规）")
class MinorModeGuardTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String KID = "kid";

    private UserMapper userMapper;
    private InMemoryRedis redis;
    private MinorModeGuard guard;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        redis = new InMemoryRedis();
        guard = new MinorModeGuard(userMapper, redis.template(), true, 30, 22, 6);
    }

    /** 固定时间点（东八区）。 */
    private static ZonedDateTime at(int hour) {
        return ZonedDateTime.of(2026, 10, 13, hour, 0, 0, 0, ZONE);
    }

    private void minorUser(String userId) {
        User u = new User(userId, "x", "default", "USER");
        u.setMinorMode(true);
        when(userMapper.selectOne(any())).thenReturn(u);
    }

    private void adultUser(String userId) {
        User u = new User(userId, "x", "default", "USER");
        u.setMinorMode(false);
        when(userMapper.selectOne(any())).thenReturn(u);
    }

    // ================= 宵禁 =================

    /** 22:00（起）命中宵禁 → 4004，文案与约定一致。 */
    @Test
    void curfewStartHour_isBlocked() {
        minorUser(KID);
        BizException ex = assertThrows(BizException.class, () -> guard.enforce(KID, at(22)));
        assertEquals(MinorModeGuard.CURFEW_CODE, ex.getCode());
        assertEquals("未成年人模式下，22:00 到 6:00 先好好休息，明天再来找我聊。", ex.getMessage());
    }

    /** 跨零点：23:00 与次日 05:00 都命中。 */
    @Test
    void curfewSpansMidnight() {
        minorUser(KID);
        assertEquals(MinorModeGuard.CURFEW_CODE, assertThrows(BizException.class,
                () -> guard.enforce(KID, at(23))).getCode());
        assertEquals(MinorModeGuard.CURFEW_CODE, assertThrows(BizException.class,
                () -> guard.enforce(KID, at(5))).getCode());
    }

    /** 区间右开：06:00 起放行；21:00 也在区间外。 */
    @Test
    void curfewEndHourIsExclusive() {
        minorUser(KID);
        assertDoesNotThrow(() -> guard.enforce(KID, at(6)));
        assertDoesNotThrow(() -> guard.enforce(KID, at(21)));
    }

    /** 宵禁命中时不该消耗每日配额（先拦，不计数）。 */
    @Test
    void curfewDoesNotConsumeDailyQuota() {
        minorUser(KID);
        assertThrows(BizException.class, () -> guard.enforce(KID, at(23)));
        assertFalse(redis.has("minor:daily:" + KID + ":20261013"));
    }

    // ================= 每日上限 =================

    /** 前 30 条放行，第 31 条 → 4005。 */
    @Test
    void thirtyFirstMessage_isRejected() {
        minorUser(KID);
        for (int i = 0; i < 30; i++) {
            guard.enforce(KID, at(12));
        }
        BizException ex = assertThrows(BizException.class, () -> guard.enforce(KID, at(12)));
        assertEquals(MinorModeGuard.DAILY_LIMIT_CODE, ex.getCode());
        assertEquals("今天已经聊了 30 条啦，明天再来吧。", ex.getMessage());
    }

    /** 计数键名带日期，且 TTL 到次日零点（12:00 → 12 小时）。 */
    @Test
    void dailyCounterKeyAndTtl() {
        minorUser(KID);
        guard.enforce(KID, at(12));
        assertEquals("1", redis.get("minor:daily:" + KID + ":20261013"));
        assertEquals(Duration.ofHours(12), redis.ttl("minor:daily:" + KID + ":20261013"));
    }

    /** ⛔ Redis 不可用时**必须放行**（合规功能不得变成故障放大器）。 */
    @Test
    void redisUnavailable_failsOpen() {
        minorUser(KID);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        MinorModeGuard g = new MinorModeGuard(userMapper, broken, true, 30, 22, 6);

        assertDoesNotThrow(() -> g.enforce(KID, at(12)));
    }

    // ================= 放行原则 =================

    /** 非未成年人：任何时段都不拦，且**不写计数**（热路径零额外 Redis 往返）。 */
    @Test
    void adultIsNeverBlocked() {
        adultUser("adult");
        assertDoesNotThrow(() -> guard.enforce("adult", at(23)));
        assertFalse(redis.has("minor:daily:adult:20261013"));
    }

    /** 开关关闭 = 接入前行为（应急回退）。 */
    @Test
    void disabledSwitchAllowsEverything() {
        MinorModeGuard off = new MinorModeGuard(userMapper, redis.template(), false, 30, 22, 6);
        assertDoesNotThrow(() -> off.enforce(KID, at(23)));
        verifyNoInteractions(userMapper);
    }

    /** 匿名/空用户直接跳过（公开聊天无 minor 概念）。 */
    @Test
    void anonymousOrBlankIsIgnored() {
        assertDoesNotThrow(() -> guard.enforce("anonymous", at(23)));
        assertDoesNotThrow(() -> guard.enforce(null, at(23)));
        assertDoesNotThrow(() -> guard.enforce("   ", at(23)));
        verifyNoInteractions(userMapper);
    }

    /** 未成年人标记有 60 秒缓存：同一用户连续多条消息只读一次库（热路径性能契约）。 */
    @Test
    void minorFlagIsCached_oneReadPerMinute() {
        minorUser(KID);
        guard.enforce(KID, at(12));
        guard.enforce(KID, at(12));
        guard.enforce(KID, at(12));
        verify(userMapper, times(1)).selectOne(any());
    }

    // ================= 回归：文案不许再泄开发参数 =================

    /** ⛔ 4004/4005 的文案是给用户看的人话：不得含 {@code continueBrake}、不得含 {@code =}。 */
    @Test
    void messagesCarryNoDeveloperParameters() {
        for (String text : new String[]{"未成年人模式下，22:00 到 6:00 先好好休息，明天再来找我聊。",
                "今天已经聊了 30 条啦，明天再来吧。"}) {
            assertThat(text).as("不得泄露开发参数：%s", text).doesNotContain("continueBrake");
            assertThat(text).as("不得含协议符号 '='：%s", text).doesNotContain("=");
        }
        // 实际抛出的文案与常量一致（上面的断言因此覆盖真实出口）
        minorUser(KID);
        BizException curfew = assertThrows(BizException.class, () -> guard.enforce(KID, at(23)));
        assertThat(curfew.getMessage()).doesNotContain("continueBrake").doesNotContain("=");
        for (int i = 0; i < 30; i++) {
            guard.enforce(KID, at(12));
        }
        BizException quota = assertThrows(BizException.class, () -> guard.enforce(KID, at(12)));
        assertThat(quota.getMessage()).doesNotContain("continueBrake").doesNotContain("=");
    }

    /** 宵禁区间边界（右开）的纯函数语义。 */
    @Test
    void inCurfewBoundaries() {
        assertTrue(guard.inCurfew(22));
        assertTrue(guard.inCurfew(23));
        assertTrue(guard.inCurfew(0));
        assertTrue(guard.inCurfew(5));
        assertFalse(guard.inCurfew(6));
        assertFalse(guard.inCurfew(12));
        assertFalse(guard.inCurfew(21));
    }

    @Test
    void thrownCurfewTextIsExact() {
        assertThatThrownBy(() -> {
            minorUser(KID);
            guard.enforce(KID, at(23));
        }).isInstanceOf(BizException.class)
          .hasMessage("未成年人模式下，22:00 到 6:00 先好好休息，明天再来找我聊。");
    }

    /**
     * ⛔ 回归：文案必须**由配置生成**，不许硬编码数字。
     * 硬编码的后果是"实际 21 点拦、文案说 22 点"——**文案撒谎**，而这是本仓最典型的复发缺陷。
     */
    @Test
    void textsFollowConfig_notHardcoded() {
        MinorModeGuard custom = new MinorModeGuard(userMapper, redis.template(), true, 7, 21, 7);
        assertThat(custom.curfewText()).contains("21:00").contains("7:00").doesNotContain("22:00");
        assertThat(custom.dailyLimitText()).contains("7 条").doesNotContain("30 条");
    }
}
