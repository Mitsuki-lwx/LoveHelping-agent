package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Year;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>注册的合规面（V34）：出生年份必填 + 未成年人判定</h3>
 *
 * <p>钉住三条线：</p>
 * <ul>
 *   <li><b>缺失/越界一律拒</b>：抛 {@link BizException}(400)「出生年份无效」，且**不落库**；</li>
 *   <li><b>未满 18 ⇒ minor_mode=1</b>：服务端据此强制宵禁/每日上限，写错这一列等于合规失效；</li>
 *   <li><b>成年/正好 18 ⇒ minor_mode=0</b>：不误伤（边界含"满 18 即成年"）。</li>
 * </ul>
 */
@DisplayName("UserService.register：出生年份与未成年人模式（V34）")
class UserServiceRegisterTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private UserMapper userMapper;
    private UserService service;
    private JwtTokenProvider jwt;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new UserService(userMapper);
        jwt = mock(JwtTokenProvider.class);
        ReflectionTestUtils.setField(service, "jwtTokenProvider", jwt);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(jwt.generateToken(anyString(), anyString(), anyString(), anyLong())).thenReturn("jwt-token");
    }

    private static int currentYear() {
        return Year.now(ZONE).getValue();
    }

    private User registeredUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ================= 非法/缺失 =================

    /** 缺失出生年份 → 400「出生年份无效」，且不落库。 */
    @Test
    void missingBirthYear_isRejected400_andNeverWrites() {
        assertThatThrownBy(() -> service.register("u1", "pw", "default", "USER", null))
                .isInstanceOf(BizException.class)
                .hasMessage("出生年份无效");
        verify(userMapper, never()).insert(any(User.class));
    }

    /** 越界（过早 / 未来）→ 400。 */
    @Test
    void outOfRangeBirthYear_isRejected400() {
        for (int bad : new int[]{1899, currentYear() + 1, 3000}) {
            assertThatThrownBy(() -> service.register("u1", "pw", "default", "USER", bad))
                    .as("非法出生年份：%d", bad)
                    .isInstanceOf(BizException.class)
                    .hasMessage("出生年份无效");
        }
        verify(userMapper, never()).insert(any(User.class));
    }

    // ================= 未成年人判定 =================

    /** 未满 18 ⇒ minor_mode=1，且出生年份原样落库。 */
    @Test
    void under18_setsMinorModeTrue() {
        int birthYear = currentYear() - 10;
        assertThat(service.register("kid", "pw", "default", "USER", birthYear)).isEqualTo("jwt-token");

        User saved = registeredUser();
        assertThat(saved.getBirthYear()).isEqualTo(birthYear);
        assertThat(saved.getMinorMode()).isTrue();
    }

    /** 成年 ⇒ minor_mode=0。 */
    @Test
    void adult_setsMinorModeFalse() {
        service.register("adult", "pw", "default", "USER", currentYear() - 30);
        assertThat(registeredUser().getMinorMode()).isFalse();
    }

    /** 边界：正好满 18 ⇒ 成年（不误伤）。 */
    @Test
    void exactly18_isNotMinor() {
        service.register("just18", "pw", "default", "USER", currentYear() - 18);
        assertThat(registeredUser().getMinorMode()).isFalse();
    }

    /** 边界：17 岁 ⇒ 未成年人。 */
    @Test
    void justUnder18_isMinor() {
        service.register("just17", "pw", "default", "USER", currentYear() - 17);
        assertThat(registeredUser().getMinorMode()).isTrue();
    }

    /** 纯函数语义（边界清晰）。 */
    @Test
    void isMinorBoundary() {
        assertThat(UserService.isMinor(2000, 2017)).isTrue();   // 17 岁
        assertThat(UserService.isMinor(2000, 2018)).isFalse();  // 18 岁
    }
}
