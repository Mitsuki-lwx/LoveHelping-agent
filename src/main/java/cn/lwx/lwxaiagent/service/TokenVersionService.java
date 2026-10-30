package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <h1>令牌版本服务（V33）—— 让不可撤销的 JWT 变得可撤销</h1>
 *
 * <p><b>背景：</b>{@link cn.lwx.lwxaiagent.tenant.JwtTokenProvider} 是纯无状态 JWT，
 * 没有黑名单，签发出去的 token 在 24 小时内一直有效。找回密码若不能踢掉旧 token，
 * 「找回」就是假的——攻击者手里那个旧 token 还能继续用满 24 小时。</p>
 *
 * <p><b>方案：</b>每个用户在 Redis 里有一个版本号 {@code auth:tv:{username}}：</p>
 * <ul>
 *   <li>签发 token 时把当前版本写进 {@code tv} 声明；</li>
 *   <li>改密 / 重置密码 / 注销账号 → {@link #bump(String)} 版本 +1 → 所有旧 token 的 {@code tv}
 *       立刻对不上 → 拦截器 401；</li>
 *   <li>热路径只读 Redis；<b>未命中才读一次库并回填缓存</b>（Redis 重启后自动恢复）。</li>
 * </ul>
 *
 * <p><b>权威顺序：</b>Redis 是判定权威（{@link #bump} 先原子自增 Redis 再写库），
 * 数据库是持久镜像。这样即使并发送达，缓存值也只会**偏大**（偏大 = 多失效几个旧 token，
 * 安全方向正确；绝不会出现"库已 +1 但缓存还是旧值 → 旧 token 仍被接受"的漏洞）。</p>
 *
 * <p><b>Redis 故障降级：</b>读写 Redis 抛异常时退回读库（仍能撤销，只是慢），
 * 不让一次 Redis 抖动把全站已登录用户打成 401。</p>
 */
@Slf4j
@Service
public class TokenVersionService {

    /** Redis key 前缀：{@code auth:tv:{username}} → 当前令牌版本。 */
    static final String KEY_PREFIX = "auth:tv:";

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;

    public TokenVersionService(StringRedisTemplate redis, UserMapper userMapper) {
        this.redis = redis;
        this.userMapper = userMapper;
    }

    /**
     * 当前令牌版本：先查 Redis（热路径，不查库），未命中则读库并回填缓存。
     *
     * @param username 用户名
     * @return 当前版本号；用户不存在时返回 0
     */
    public long currentVersion(String username) {
        String key = KEY_PREFIX + username;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            // Redis 不可用：直接以库为准（撤销仍生效，只是每个请求多一次读库）
            log.warn("令牌版本缓存读取失败，退回读库：user={}, err={}", username, e.getMessage());
            return readDbVersion(username);
        }
        long fromDb = readDbVersion(username);
        try {
            redis.opsForValue().set(key, String.valueOf(fromDb));   // 回填缓存
        } catch (Exception e) {
            log.warn("令牌版本缓存回填失败（不影响本次判定）：user={}, err={}", username, e.getMessage());
        }
        return fromDb;
    }

    /**
     * token 里的 {@code tv} 是否仍是当前版本（不一致 = 该 token 已被撤销）。
     */
    public boolean isCurrent(String username, long tokenVersion) {
        return currentVersion(username) == tokenVersion;
    }

    /**
     * 版本 +1：改密 / 重置密码 / 注销账号后调用，使该用户**所有已签发的 token 立即失效**。
     *
     * <p>先原子自增 Redis（判定权威，且保证单调不回退），再写库做持久镜像。</p>
     */
    public void bump(String username) {
        String key = KEY_PREFIX + username;
        long next;
        try {
            currentVersion(username);                       // 确保 key 存在（未命中先回填库值）
            Long value = redis.opsForValue().increment(key); // 原子自增
            next = value != null ? value : readDbVersion(username) + 1;
        } catch (Exception e) {
            log.warn("令牌版本自增失败（Redis 不可用），改为直接写库：user={}, err={}", username, e.getMessage());
            next = readDbVersion(username) + 1;
        }
        // ⛔ 用**字符串列名**的 UpdateWrapper（而不是 LambdaUpdateWrapper.set）：
        //    lambda 版 set() 会**立即**解析列名并依赖 MyBatis-Plus 的 TableInfo 缓存，
        //    在纯单测（无 Spring/MyBatis 上下文）里直接抛 "can not find lambda cache"。
        //    与 DeleteService 既有写法（UpdateWrapper + 字符串列）保持一致。
        userMapper.update(null, new UpdateWrapper<User>()
                .eq("username", username)
                .set("token_version", next));
        log.info("令牌版本已提升（旧 token 全部失效）：user={}, tokenVersion={}", username, next);
    }

    /** 读库取版本（用户不存在或字段为空 → 0，与列默认值一致）。 */
    private long readDbVersion(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return (user == null || user.getTokenVersion() == null) ? 0L : user.getTokenVersion();
    }
}
