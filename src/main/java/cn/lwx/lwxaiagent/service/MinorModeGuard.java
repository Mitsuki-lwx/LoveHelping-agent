package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>未成年人模式（V34 合规）—— 服务端强制</h1>
 *
 * <p><b>这是合规要求</b>（未成年人网络保护），不是可选的体验优化：判定与拦截都在服务端，
 * 前端无法绕过。命中后由 {@code ChatEntry.guardrailCheck} 在聊天入口（与情绪刹车片同层）抛出，
 * 文案本身就是给用户看的人话，走默认 data 事件，前端无需特殊渲染。</p>
 *
 * <h2>两条强制线</h2>
 * <ol>
 *   <li><b>宵禁</b>：当前小时 ∈ {@code [curfew-start-hour, curfew-end-hour)}（支持跨零点）
 *       → {@link BizException} {@link #CURFEW_CODE}；</li>
 *   <li><b>每日上限</b>：Redis 计数 {@code minor:daily:{userId}:{yyyyMMdd}}，首次 INCR 时
 *       过期到次日零点；超过 {@code daily-message-limit} → {@link BizException} {@link #DAILY_LIMIT_CODE}。</li>
 * </ol>
 *
 * <h2>为什么这样写（性能）</h2>
 * <p>{@code ChatEntry} 是每请求都走的热点路径，所以：</p>
 * <ul>
 *   <li>用户是否未成年人用 <b>一次按用户名读 + 60 秒小缓存</b>（{@link ConcurrentHashMap}）——
 *       同一个用户一分钟内只读一次库，不会每条消息都打一次数据库；</li>
 *   <li>非未成年人 / 开关关闭 / 匿名用户 <b>在缓存判定后立即返回</b>，零额外开销；</li>
 *   <li>命中未成年人后才多一次 Redis 往返（每日计数）。</li>
 * </ul>
 *
 * <h2>Redis 不可用时必须放行</h2>
 * <p>每日计数的 Redis 调用异常时 <b>放行并记 warn</b>——绝不能因为缓存挂了
 * 把未成年人挡在门外（那会让合规功能变成故障放大器）。</p>
 *
 * <p>⛔ 文案里不得出现任何开发参数（如 {@code continueBrake=true}）——参数属于协议，
 * 不该出现在给用户的人话里（参考 4002 的教训）。</p>
 */
@Slf4j
@Service
public class MinorModeGuard {

    /** 宵禁命中业务码。 */
    public static final int CURFEW_CODE = 4004;
    /** 每日条数超限业务码。 */
    public static final int DAILY_LIMIT_CODE = 4005;

    /**
     * 宵禁文案（给用户看的人话，不含任何开发参数）。
     *
     * <p>⛔ 由**配置**生成，不写死数字：写死的话，把 {@code curfew-start-hour} 改成 21
     * 就会变成"实际 21 点拦、文案说 22 点"——**文案撒谎**是本仓最典型的复发缺陷
     * （"默认值写在两处只改一处"）。</p>
     */
    String curfewText() {
        return String.format("未成年人模式下，%d:00 到 %d:00 先好好休息，明天再来找我聊。",
                curfewStart, curfewEnd);
    }

    /** 每日上限文案（同上：由配置生成）。 */
    String dailyLimitText() {
        return String.format("今天已经聊了 %d 条啦，明天再来吧。", dailyLimit);
    }

    /** 未成年人标记的缓存存活时间。 */
    static final long CACHE_TTL_MS = 60_000L;
    /** 缓存条目上限（超出即整体清空，避免无界增长）。 */
    static final int MAX_CACHE_ENTRIES = 10_000;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int dailyLimit;
    private final int curfewStart;
    private final int curfewEnd;

    private final ConcurrentHashMap<String, CacheEntry> minorCache = new ConcurrentHashMap<>();

    public MinorModeGuard(UserMapper userMapper, StringRedisTemplate redis,
                          @Value("${app.minor.enabled:true}") boolean enabled,
                          @Value("${app.minor.daily-message-limit:30}") int dailyLimit,
                          @Value("${app.minor.curfew-start-hour:22}") int curfewStart,
                          @Value("${app.minor.curfew-end-hour:6}") int curfewEnd) {
        this.userMapper = userMapper;
        this.redis = redis;
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.curfewStart = curfewStart;
        this.curfewEnd = curfewEnd;
    }

    /** 生产入口：用当前时间（东八区）执行强制。 */
    public void enforce(String userId) {
        enforce(userId, ZonedDateTime.now(ZONE));
    }

    /**
     * 可注入时间版本的强制逻辑（测试用固定时间点，避免依赖真实钟表）。
     *
     * @throws BizException {@link #CURFEW_CODE} 宵禁命中；{@link #DAILY_LIMIT_CODE} 每日超限
     */
    void enforce(String userId, ZonedDateTime now) {
        if (!enabled || userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return;
        }
        if (!isMinor(userId)) {
            return;
        }
        if (inCurfew(now.getHour())) {
            throw new BizException(CURFEW_CODE, curfewText());
        }
        enforceDailyLimit(userId, now);
    }

    /**
     * 宵禁判定：{@code [start, end)}；跨零点（start &gt; end）时正确折返。
     * <p>例：22~6 → 22:00 命中、次日 05:59 命中、06:00 起放行。</p>
     */
    boolean inCurfew(int hour) {
        return curfewStart <= curfewEnd
                ? hour >= curfewStart && hour < curfewEnd
                : hour >= curfewStart || hour < curfewEnd;
    }

    /**
     * 每日条数上限：Redis 计数 + 首次设过期到次日零点；Redis 不可用则放行（记 warn）。
     */
    private void enforceDailyLimit(String userId, ZonedDateTime now) {
        String key = "minor:daily:" + userId + ":" + now.format(DAY);
        Long used;
        try {
            used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                redis.expire(key, Duration.ofSeconds(secondsUntilNextMidnight(now)));
            }
        } catch (RuntimeException e) {
            // 合规功能不得变成故障放大器：缓存挂了也放行
            log.warn("未成年人每日限额校验跳过（Redis 不可用，放行）：user={}, err={}", userId, e.getMessage());
            return;
        }
        if (used != null && used > dailyLimit) {
            throw new BizException(DAILY_LIMIT_CODE, dailyLimitText());
        }
    }

    private static long secondsUntilNextMidnight(ZonedDateTime now) {
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(ZONE);
        return Math.max(1L, Duration.between(now, nextMidnight).getSeconds());
    }

    /**
     * 是否未成年人：一次按用户名读 + 60 秒缓存。
     * <p>查询异常时按"非未成年人"处理（放行）——与本类的放行原则一致。</p>
     */
    private boolean isMinor(String userId) {
        long nowMs = System.currentTimeMillis();
        CacheEntry cached = minorCache.get(userId);
        if (cached != null && cached.expiresAtMs > nowMs) {
            return cached.minor;
        }
        boolean minor;
        try {
            User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, userId));
            minor = u != null && Boolean.TRUE.equals(u.getMinorMode());
        } catch (RuntimeException e) {
            log.warn("未成年人标记查询失败，按非未成年人放行：user={}, err={}", userId, e.getMessage());
            return false;
        }
        if (minorCache.size() > MAX_CACHE_ENTRIES) {
            minorCache.clear();
        }
        minorCache.put(userId, new CacheEntry(minor, nowMs + CACHE_TTL_MS));
        return minor;
    }

    private record CacheEntry(boolean minor, long expiresAtMs) {}
}
