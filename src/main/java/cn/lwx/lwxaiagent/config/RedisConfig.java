package cn.lwx.lwxaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * <h1>Redis 配置类</h1>
 *
 * <p>自定义 Redis 的序列化方式，替代 Spring Boot 默认的 JDK 序列化。</p>
 *
 * <p><b>为什么需要自定义序列化？</b></p>
 * <ul>
 *   <li>默认的 JDK 序列化器（{@code JdkSerializationRedisSerializer}）会将 Java 对象序列化为字节流，
 *       存到 Redis 后是二进制乱码，用 Redis CLI 查看时完全不可读</li>
 *   <li>JDK 序列化的字节流比较臃肿，占用更多内存和网络带宽</li>
 *   <li>改用 String 序列化器后，Redis 中存储的是可读的字符串，方便调试和监控</li>
 * </ul>
 *
 * <p><b>两个模板的区别：</b></p>
 * <ul>
 *   <li>{@code StringRedisTemplate}：key 和 value 都是 String 的专用模板（最简单的场景）</li>
 *   <li>{@code RedisTemplate<String, Object>}：key 是 String，value 可以是任意 Object 的通用模板</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * 创建 StringRedisTemplate Bean
     *
     * <p>StringRedisTemplate 继承自 RedisTemplate，泛型固定为 &lt;String, String&gt;，
     * 适用于 key 和 value 都是纯字符串的场景（如缓存 token、验证码等）。</p>
     *
     * @param factory Redis 连接工厂（Spring Boot 自动配置会创建）
     * @return StringRedisTemplate 实例
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 创建通用的 RedisTemplate Bean
     *
     * <p>泛型为 &lt;String, Object&gt;：key 固定为 String 类型，value 可以是任意 Java 对象。</p>
     *
     * <p><b>序列化器配置说明：</b></p>
     * <ul>
     *   <li>{@code keySerializer}：key 的序列化方式 → StringRedisSerializer（存为可读字符串）</li>
     *   <li>{@code valueSerializer}：value 的序列化方式 → StringRedisSerializer（存为可读字符串）</li>
     *   <li>{@code hashKeySerializer}：Hash 结构中 key 的序列化方式</li>
     *   <li>{@code hashValueSerializer}：Hash 结构中 value 的序列化方式</li>
     * </ul>
     *
     * <p><b>注意：</b>value 也使用 StringRedisSerializer 意味着存入的 Object
     * 需要先转换为 String。如果直接存一个 User 对象会抛异常。如需存储复杂对象，
     * 应改用 Jackson2JsonRedisSerializer 或 GenericJackson2JsonRedisSerializer。</p>
     *
     * @param factory Redis 连接工厂
     * @return 配置好序列化器的 RedisTemplate 实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);            // 设置连接工厂（连接到哪个 Redis 服务器）

        // 全部使用 String 序列化器——让 Redis 中存储的数据是人类可读的字符串
        template.setKeySerializer(new StringRedisSerializer());        // key 序列化为可读字符串
        template.setValueSerializer(new StringRedisSerializer());      // value 序列化为可读字符串
        template.setHashKeySerializer(new StringRedisSerializer());    // Hash 的 key 序列化
        template.setHashValueSerializer(new StringRedisSerializer());  // Hash 的 value 序列化

        return template;
    }
}
