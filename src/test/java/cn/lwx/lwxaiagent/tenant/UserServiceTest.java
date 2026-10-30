package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>UserService 单测：updateProfile（V31 纪念日）+ 邮箱绑定/重置密码（V33）</h3>
 *
 * <p>钉住四条线：</p>
 * <ul>
 *   <li><b>合法日期落库</b>：ISO {@code yyyy-MM-dd} → {@link LocalDate}，写进 users.anniversary_date，
 *       且读回一致（前端首页「在一起第 N 天」的数据源）；</li>
 *   <li><b>非法日期显式报错且不落库</b>：抛 {@link BizException}，并且 <b>updateById 一次都不能调</b>——
 *       静默忽略会让用户以为已经存上（本仓最忌讳的"假装成功"）；</li>
 *   <li><b>回归守卫</b>：不传/传空 anniversaryDate 时，既有字段（昵称/头像/签名）行为完全不变；</li>
 *   <li><b>V33</b>：绑定邮箱写入 email + email_verified_at；改密/重置密码必须 bump 令牌版本
 *       （少了这一步，"找回密码"就踢不掉攻击者手里的旧 token）。</li>
 * </ul>
 */
@DisplayName("UserService：纪念日（V31）/ 邮箱绑定与重置密码（V33）")
class UserServiceTest {

    private UserMapper userMapper;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        service = new UserService(userMapper);
    }

    /** 库里已存在的一条用户记录（昵称已有旧值，纪念日未设置） */
    private User storedUser() {
        User u = new User("u1", "x", "default", "USER");
        u.setNickname("旧昵称");
        return u;
    }

    // ================= ① 合法日期：落库 + 读回一致 =================

    @Test
    void legalAnniversary_persistsLocalDate_andReadsBackSame() {
        User stored = storedUser();
        when(userMapper.selectOne(any())).thenReturn(stored);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        boolean changed = service.updateProfile("u1", null, null, null, "2023-05-20");

        assertThat(changed).isTrue();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getAnniversaryDate()).isEqualTo(LocalDate.of(2023, 5, 20));

        // 读回一致：落库的那行数据（同实体）经 findProfile 拿到的仍是同一日期
        when(userMapper.selectOne(any())).thenReturn(captor.getValue());
        assertThat(service.findProfile("u1").getAnniversaryDate()).isEqualTo(LocalDate.of(2023, 5, 20));
    }

    /** 两端日期（年初/年末）不能被解析或截断搞错 */
    @Test
    void legalAnniversary_parsesBoundaryDates() {
        User stored = storedUser();
        when(userMapper.selectOne(any())).thenReturn(stored);

        assertThat(service.updateProfile("u1", null, null, null, "2024-01-01")).isTrue();
        assertThat(stored.getAnniversaryDate()).isEqualTo(LocalDate.of(2024, 1, 1));

        assertThat(service.updateProfile("u1", null, null, null, "2024-12-31")).isTrue();
        assertThat(stored.getAnniversaryDate()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    // ================= ② 非法日期：显式报错 + 不落库 =================

    /** 格式合法但不存在的日期（2023 年没有 2 月 30 日）→ BizException，且绝不写库 */
    @Test
    void impossibleDate_throwsBizException_andNeverWrites() {
        when(userMapper.selectOne(any())).thenReturn(storedUser());

        assertThatThrownBy(() -> service.updateProfile("u1", null, null, null, "2023-02-30"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("纪念日格式不正确");

        verify(userMapper, never()).updateById(any(User.class));
    }

    /** 非 ISO 格式（斜杠 / 无分隔符 / 纯文本）→ BizException，且绝不写库 */
    @Test
    void malformedDate_throwsBizException_andNeverWrites() {
        when(userMapper.selectOne(any())).thenReturn(storedUser());

        for (String bad : new String[]{"2023/05/20", "20230520", "2023-5-20", "昨天", "2023-13-01"}) {
            assertThatThrownBy(() -> service.updateProfile("u1", null, null, null, bad))
                    .as("非法纪念日：%s", bad)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("纪念日格式不正确");
        }

        verify(userMapper, never()).updateById(any(User.class));
    }

    /** 非法日期与合法字段同时提交 → 整体拒绝（不能只写昵称、日期假装没看见） */
    @Test
    void illegalAnniversary_rejectsWholeUpdate_noPartialWrite() {
        when(userMapper.selectOne(any())).thenReturn(storedUser());

        assertThatThrownBy(() -> service.updateProfile("u1", "新昵称", "❤️", "签名", "not-a-date"))
                .isInstanceOf(BizException.class);

        verify(userMapper, never()).updateById(any(User.class));
    }

    // ================= ③ 回归守卫：不传/空 → 既有行为不变 =================

    /** 不传纪念日 → 昵称照常更新，anniversary_date 保持原值（null） */
    @Test
    void omittedAnniversary_updatesExistingFieldsOnly() {
        User stored = storedUser();
        when(userMapper.selectOne(any())).thenReturn(stored);

        boolean changed = service.updateProfile("u1", "新昵称", null, null, null);

        assertThat(changed).isTrue();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("新昵称");
        assertThat(captor.getValue().getAnniversaryDate()).isNull();
    }

    /** 空串纪念日同样视为「不修改」（与既有字段一致） */
    @Test
    void blankAnniversary_isTreatedAsUnchanged() {
        User stored = storedUser();
        when(userMapper.selectOne(any())).thenReturn(stored);

        boolean changed = service.updateProfile("u1", null, null, null, "   ");

        assertThat(changed).isFalse();
        assertThat(stored.getAnniversaryDate()).isNull();
        verify(userMapper, never()).updateById(any(User.class));
    }

    /** 全空 body → 无更新、无写入（既有契约不变） */
    @Test
    void allBlank_returnsFalse_andNeverWrites() {
        when(userMapper.selectOne(any())).thenReturn(storedUser());

        assertThat(service.updateProfile("u1", null, null, null, null)).isFalse();

        verify(userMapper, never()).updateById(any(User.class));
    }

    // ================= V33：邮箱绑定 / 重置密码（旧 token 失效） =================

    /** 绑定邮箱 → 落库 email + email_verified_at（只有验证过的邮箱才能用于找回）。 */
    @Test
    void bindEmail_writesEmailAndVerifiedAt() {
        User stored = storedUser();
        when(userMapper.selectOne(any())).thenReturn(stored);

        String bound = service.bindEmail("u1", "a@b.com");

        assertThat(bound).isEqualTo("a@b.com");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("a@b.com");
        assertThat(captor.getValue().getEmailVerifiedAt()).isNotNull();
    }

    /** 重置密码 → 新密码可用（BCrypt）+ **令牌版本 +1**（旧 token 立即失效）。 */
    @Test
    void resetPassword_rehashesPassword_andBumpsTokenVersion() {
        cn.lwx.lwxaiagent.service.TokenVersionService versions =
                mock(cn.lwx.lwxaiagent.service.TokenVersionService.class);
        ReflectionTestUtils.setField(service, "tokenVersionService", versions);

        org.springframework.security.crypto.password.PasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        User stored = new User("u1", encoder.encode("OldPassw0rd!"), "default", "USER");
        when(userMapper.selectOne(any())).thenReturn(stored);

        service.resetPassword("u1", "NewPassw0rd!9");

        assertThat(encoder.matches("NewPassw0rd!9", stored.getPassword())).isTrue();
        assertThat(encoder.matches("OldPassw0rd!", stored.getPassword())).isFalse();
        verify(userMapper).updateById(stored);
        verify(versions).bump("u1");   // ⛔ 少了这一句，"找回密码"就踢不掉攻击者的旧 token
    }

    /** 新密码过短 → 400，且不写库、不动令牌版本。 */
    @Test
    void resetPassword_tooShort_throws400_andNeverWrites() {
        cn.lwx.lwxaiagent.service.TokenVersionService versions =
                mock(cn.lwx.lwxaiagent.service.TokenVersionService.class);
        ReflectionTestUtils.setField(service, "tokenVersionService", versions);
        when(userMapper.selectOne(any())).thenReturn(storedUser());

        assertThatThrownBy(() -> service.resetPassword("u1", "short"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("新密码长度至少 8 位");

        verify(userMapper, never()).updateById(any(User.class));
        verify(versions, never()).bump(any());
    }

    /** 改密（需旧密码）同样必须 bump —— 否则"改密码踢掉其它设备"是空话。 */
    @Test
    void changePassword_bumpsTokenVersion() {
        cn.lwx.lwxaiagent.service.TokenVersionService versions =
                mock(cn.lwx.lwxaiagent.service.TokenVersionService.class);
        ReflectionTestUtils.setField(service, "tokenVersionService", versions);

        org.springframework.security.crypto.password.PasswordEncoder encoder =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        User stored = new User("u1", encoder.encode("OldPassw0rd!"), "default", "USER");
        when(userMapper.selectOne(any())).thenReturn(stored);

        service.changePassword("u1", "OldPassw0rd!", "NewPassw0rd!9");

        verify(versions).bump("u1");
    }

    /** 邮箱是否被占用：排除自己（本人重新绑定同一邮箱不算冲突）。 */
    @Test
    void emailTaken_excludesCurrentUser() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.emailTaken("a@b.com", "u1")).isFalse();

        when(userMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.emailTaken("a@b.com", "u1")).isTrue();
    }

    // ================= V34：进化模块单独同意（docs/07 §5.4） =================

    /** 同意 → 落库 true（此后其对话才可能被提炼为技能）。 */
    @Test
    void updateEvolutionConsent_persistsFlag() {
        User stored = storedUser();
        when(userMapper.selectOne(any())).thenReturn(stored);

        assertThat(service.updateEvolutionConsent("u1", true)).isTrue();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getEvolutionConsent()).isTrue();
    }

    /** 撤回同意 → 落库 false。 */
    @Test
    void updateEvolutionConsent_canRevoke() {
        User stored = storedUser();
        when(userMapper.selectOne(any())).thenReturn(stored);

        service.updateEvolutionConsent("u1", false);

        assertThat(stored.getEvolutionConsent()).isFalse();
        verify(userMapper).updateById(stored);
    }
}
