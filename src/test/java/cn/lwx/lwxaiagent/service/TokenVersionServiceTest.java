package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import cn.lwx.lwxaiagent.support.InMemoryRedis;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 令牌版本服务（V33）—— JWT 撤销的判定核心。
 *
 * <p>钉住三件事：</p>
 * <ul>
 *   <li><b>热路径不查库</b>：Redis 命中时绝不 select（拦截器每个请求都会调它）；</li>
 *   <li><b>未命中读库 + 回填</b>：Redis 重启后自动恢复，且回填的是库里的真实版本；</li>
 *   <li><b>bump 后判定翻转</b>：版本 +1 → 旧 token 的 tv 立刻不一致。</li>
 * </ul>
 */
@DisplayName("TokenVersionService：缓存/回填/递增")
class TokenVersionServiceTest {

    private InMemoryRedis redis;
    private UserMapper userMapper;
    private TokenVersionService service;

    @BeforeEach
    void setUp() {
        redis = new InMemoryRedis();
        userMapper = mock(UserMapper.class);
        service = new TokenVersionService(redis.template(), userMapper);
    }

    private User storedUser(int tokenVersion) {
        User user = new User("u1", "hash", "default", "USER");
        user.setTokenVersion(tokenVersion);
        return user;
    }

    /** 缓存命中 → 直接返回缓存值，且**一次库都不查**（热路径承诺）。 */
    @Test
    void cacheHit_returnsCachedValue_withoutTouchingDb() {
        redis.seed("auth:tv:u1", "3");

        assertThat(service.currentVersion("u1")).isEqualTo(3);
        assertThat(service.isCurrent("u1", 3)).isTrue();
        assertThat(service.isCurrent("u1", 2)).isFalse();
        verify(userMapper, never()).selectOne(any());
    }

    /** 缓存未命中 → 读一次库，并把库里的版本回填进缓存。 */
    @Test
    void cacheMiss_readsDbOnce_andBackfillsCache() {
        when(userMapper.selectOne(any())).thenReturn(storedUser(7));

        assertThat(service.currentVersion("u1")).isEqualTo(7);
        assertThat(redis.get("auth:tv:u1")).isEqualTo("7");   // 已回填
        verify(userMapper).selectOne(any());
    }

    /** 用户不存在（或列为空）→ 版本 0，与数据库默认值一致。 */
    @Test
    void unknownUser_treatedAsVersionZero() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThat(service.currentVersion("ghost")).isZero();
        assertThat(service.isCurrent("ghost", 0)).isTrue();
    }

    /** bump：Redis 原子自增 + 写库（版本落到 5）→ 旧 tv=4 的 token 立刻不再有效。 */
    @Test
    void bump_incrementsCacheAndPersistsToDb_thenOldVersionIsRejected() {
        redis.seed("auth:tv:u1", "4");
        assertThat(service.isCurrent("u1", 4)).isTrue();

        service.bump("u1");

        assertThat(redis.get("auth:tv:u1")).isEqualTo("5");
        assertThat(service.isCurrent("u1", 4)).isFalse();   // 旧 token 被撤销
        assertThat(service.isCurrent("u1", 5)).isTrue();    // 新签发的 token 有效

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<User>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(userMapper).update(isNull(), captor.capture());
        Map<String, Object> params =
                ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper) captor.getValue())
                        .getParamNameValuePairs();
        assertThat(params.values()).contains(5L);          // 落库的就是新版本
    }

    /** 缓存未命中时 bump：先回填库值（0）再 +1 → 1，且写库。 */
    @Test
    void bump_withColdCache_backfillsThenIncrements() {
        when(userMapper.selectOne(any())).thenReturn(storedUser(0));

        service.bump("u1");

        assertThat(redis.get("auth:tv:u1")).isEqualTo("1");
    }
}
