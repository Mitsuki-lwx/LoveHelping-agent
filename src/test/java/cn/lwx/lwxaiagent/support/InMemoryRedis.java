package cn.lwx.lwxaiagent.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试用的「内存版 StringRedisTemplate」。
 *
 * <p>本仓的单测约定是 <b>零外部依赖</b>（`mvn test` 不连真 Redis），但验证码/令牌版本的
 * 行为（TTL、一次性、计数、限流）恰恰全在 Redis 语义里——只 mock「返回 1」等于什么都没测。
 * 因此这里用 Mockito 实现一个带真实语义的极小替身：get/set(带 TTL)/setIfAbsent/increment/expire/delete
 * 全部落在 {@code HashMap} 上。</p>
 *
 * <p>它不是"断言不抛异常"的假测试：{@link #get}、{@link #ttl}、{@link #has} 让测试可以直接
 * 检查"存进去的到底是什么值、TTL 多久"。</p>
 */
public final class InMemoryRedis {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Duration> ttls = new HashMap<>();
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    public InMemoryRedis() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);

        when(ops.get(anyString())).thenAnswer(i -> values.get(i.getArgument(0, String.class)));

        doAnswer(i -> {
            String key = i.getArgument(0);
            values.put(key, i.getArgument(1));
            ttls.put(key, i.getArgument(2));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        doAnswer(i -> {
            values.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString());

        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(i -> {
            String key = i.getArgument(0);
            if (values.containsKey(key)) {
                return false;
            }
            values.put(key, i.getArgument(1));
            ttls.put(key, i.getArgument(2));
            return true;
        });

        when(ops.increment(anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            long next = Long.parseLong(values.getOrDefault(key, "0")) + 1;
            values.put(key, String.valueOf(next));
            return next;
        });

        when(template.expire(anyString(), any(Duration.class))).thenAnswer(i -> {
            ttls.put(i.getArgument(0), i.getArgument(1));
            return true;
        });

        when(template.delete(anyString())).thenAnswer(i -> {
            String key = i.getArgument(0);
            values.remove(key);
            ttls.remove(key);
            return true;
        });
    }

    /** 供被测类注入的模板。 */
    public StringRedisTemplate template() {
        return template;
    }

    /** 预置一个键值（测试夹具用，不带 TTL）。 */
    public void seed(String key, String value) {
        values.put(key, value);
    }

    /** 当前值（不存在返回 null）。 */
    public String get(String key) {
        return values.get(key);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** 写入时记录的 TTL（用于断言"5 分钟""1 小时"这类关键参数）。 */
    public Duration ttl(String key) {
        return ttls.get(key);
    }

    /** 模拟 TTL 到期 / 手动清除。 */
    public void expireNow(String key) {
        values.remove(key);
        ttls.remove(key);
    }
}
