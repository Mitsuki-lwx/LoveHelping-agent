package cn.lwx.lwxaiagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * <h1>基于 Redis 的每日速率限制器</h1>
 * <p>
 * 本类实现了一个简单而高效的每日 API 调用配额控制系统。
 * 使用 Redis 作为分布式计数器，确保在多个服务实例（水平扩展）场景下
 * 也能准确统计每个租户的当日 API 调用次数。
 * </p>
 *
 * <h2>设计原理</h2>
 * <p>
 * 采用"固定窗口计数器"（Fixed Window Counter）算法：
 * </p>
 * <ol>
 *   <li>为每个租户每天创建一个 Redis 键，格式为 {@code rate:{tenantId}:{yyyy-MM-dd}}</li>
 *   <li>每次调用时递增该键的值（使用 Redis INCR 原子操作）</li>
 *   <li>当键首次创建时（值为 1），设置其过期时间为当天结束时刻</li>
 *   <li>当计数达到配额上限时，拒绝后续请求并抛出异常</li>
 * </ol>
 *
 * <h2>时区处理</h2>
 * <p>
 * 所有时间计算基于<strong>亚洲/上海（Asia/Shanghai）</strong>时区，
 * 确保配额重置时间与中国用户的自然日对齐（每天 00:00 重置）。
 * </p>
 *
 * <h2>分布式一致性</h2>
 * <p>
 * Redis 的 {@code INCR} 命令是<strong>原子操作</strong>，天然支持分布式环境。
 * 多个服务实例同时递增同一个键不会产生竞态条件。
 * 但请注意，check-then-increment 是两个独立操作，在高并发下可能略微超出配额。
 * 对于本系统的业务场景（每日免费次数限制），这个精度是可接受的。
 * </p>
 *
 * <h2>可配置性</h2>
 * <p>
 * 通过 Spring 配置文件（application.yml）控制：
 * </p>
 * <ul>
 *   <li>{@code app.rate-limit.enabled}：是否启用速率限制（默认 true）</li>
 *   <li>{@code app.rate-limit.daily-quota}：每日配额上限（默认 5）</li>
 * </ul>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see StringRedisTemplate Redis 操作模板
 */
@Slf4j
@Service
public class RateLimiter {

    /**
     * Spring Data Redis 的字符串操作模板，用于执行 Redis 的 GET、SET、INCR、EXPIRE 等命令。
     * 使用 String 序列化器，直接操作字符串值。
     */
    private final StringRedisTemplate redis;

    /**
     * 速率限制的总开关。
     * 当设为 {@code false} 时，所有限制逻辑被跳过，
     * 适用于开发环境、测试环境或付费用户不计次数的场景。
     */
    private final boolean enabled;

    /**
     * 每个租户每日的 API 调用配额上限。
     * 一旦达到上限，该租户当天将无法再调用 API，
     * 直到次日 00:00（Asia/Shanghai 时区）自动重置。
     */
    private final int dailyQuota;

    /**
     * <h3>构造函数 - 依赖注入</h3>
     *
     * @param redis      Redis 操作模板，用于读写 Redis 中的计数器
     * @param enabled    速率限制开关，从配置项 {@code app.rate-limit.enabled} 读取，默认 true
     * @param dailyQuota 每日配额上限，从配置项 {@code app.rate-limit.daily-quota} 读取，默认 5
     */
    public RateLimiter(StringRedisTemplate redis,
                       @Value("${app.rate-limit.enabled:true}") boolean enabled,
                       @Value("${app.rate-limit.daily-quota:5}") int dailyQuota) {
        this.redis = redis;
        this.enabled = enabled;
        this.dailyQuota = dailyQuota;
    }

    /**
     * <h3>检查配额 —— 在 API 调用前执行</h3>
     * <p>
     * 检查指定租户当日的 API 调用次数是否已达上限。
     * 若已达上限则抛出 {@link cn.lwx.lwxaiagent.common.BizException}，
     * 状态码为 429（Too Many Requests）。
     * </p>
     *
     * <h4>安全机制</h4>
     * <p>
     * 采用"先检查后执行"的模式（Check-Then-Act），
     * 虽然在高并发下可能略微超出配额（至多超出并发请求数），
     * 但性能开销极小，不需要 Redis 事务或 Lua 脚本。
     * </p>
     *
     * <h4>何时跳过检查</h4>
     * <p>
     * 当 {@code enabled} 为 {@code false} 时，本方法直接返回，不做任何限制。
     * </p>
     *
     * @param tenantId 租户 ID，用于区分不同租户的配额计数
     * @throws cn.lwx.lwxaiagent.common.BizException 当配额耗尽时抛出，
     *         异常消息包含已用次数和配额上限的友好提示
     */
    public void checkQuota(String tenantId) {
        if (!enabled) return;

        String key = key(tenantId);
        String val = redis.opsForValue().get(key);
        int used = val != null ? Integer.parseInt(val) : 0;

        if (used >= dailyQuota) {
            log.info("Rate limit hit: tenant={}, used={}/{}", tenantId, used, dailyQuota);
            throw new cn.lwx.lwxaiagent.common.BizException(429,
                    String.format("今日免费次数已用完（%d/%d），开通会员可继续使用", used, dailyQuota));
        }
    }

    /**
     * <h3>递增计数 —— 在 API 调用成功后执行</h3>
     * <p>
     * 对指定租户当日的 API 调用计数器执行原子递增（Redis INCR）。
     * 如果这是当天的第一次调用（计数器值为 1），则设置键的过期时间为当天结束时刻。
     * </p>
     *
     * <h4>首次调用处理</h4>
     * <p>
     * 当 Redis 键首次创建时，需要设置其 TTL（过期时间），
     * 确保键在当天结束时自动删除，不会残留到第二天。
     * 这里通过检查 INCR 返回值是否为 1 来判断是否是首次创建该键。
     * </p>
     *
     * <h4>何时跳过递增</h4>
     * <p>
     * 当 {@code enabled} 为 {@code false} 时，本方法直接返回，不执行任何 Redis 操作。
     * </p>
     *
     * @param tenantId 租户 ID，用于区分不同租户的配额计数
     */
    public void increment(String tenantId) {
        if (!enabled) return;

        String key = key(tenantId);
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1) {
            // 首次创建键，设置过期时间为当天结束
            redis.expire(key, ttlUntilEndOfDay());
        }
    }

    /**
     * <h3>构造 Redis 键名</h3>
     * <p>
     * 键名格式：{@code rate:{tenantId}:{yyyy-MM-dd}}
     * </p>
     * <p>
     * 例如租户 "tenant_01" 在 2026-07-25 的键名为：{@code rate:tenant_01:2026-07-25}
     * </p>
     *
     * @param tenantId 租户 ID
     * @return Redis 键名字符串，包含租户标识和日期信息
     */
    // ADR-13：限流对象由租户改为用户
    private String key(String userId) {
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        return "rate:user:" + userId + ":" + date;
    }

    /**
     * <h3>计算当前时刻到当天结束的剩余时间</h3>
     * <p>
     * 使用 Asia/Shanghai 时区的当前时间到当天 23:59:59.999... 的时间差。
     * </p>
     * <p>
     * 注意：{@link LocalTime#MAX} 的值为 {@code 23:59:59.999999999}，
     * 因此 TTL 最大约为 24 小时。在 Spring Data Redis 中，
     * 如果 TTL 超过 {@link Integer#MAX_VALUE} 毫秒（约 24.8 天），
     * 需要特殊处理，但本场景的 TTL 最多 24 小时，不会超限。
     * </p>
     *
     * @return {@link Duration} 对象，表示到当天结束的剩余时间
     */
    private static Duration ttlUntilEndOfDay() {
        return Duration.between(LocalTime.now(ZoneId.of("Asia/Shanghai")), LocalTime.MAX);
    }
}
