package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.User;
import cn.lwx.lwxaiagent.mapper.UserMapper;
import cn.lwx.lwxaiagent.service.TokenVersionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * <h1>用户服务 —— 注册与登录认证</h1>
 * <p>
 * 本服务负责处理用户注册和登录的核心业务逻辑，包括密码加密、身份验证和 JWT 令牌签发。
 * 它是系统安全体系的第一道防线，所有用户的身份认证都通过本服务完成。
 * </p>
 *
 * <h2>密码安全 —— BCrypt 加密</h2>
 * <p>
 * 使用 {@link BCryptPasswordEncoder} 对用户密码进行<strong>单向哈希</strong>加密。
 * BCrypt 是当前业界公认的安全密码哈希算法，具有以下特性：
 * </p>
 * <ul>
 *   <li><b>自带盐值（Salt）</b>：每次加密自动生成随机盐值，
 *       相同的密码两次加密结果不同，有效抵御彩虹表攻击</li>
 *   <li><b>自适应计算成本</b>：可以通过 increasing log rounds 提高计算复杂度，
 *       对抗硬件性能提升带来的暴力破解加速</li>
 *   <li><b>不可逆</b>：无法从哈希值反向推导出原始密码，
 *       即使数据库泄露，攻击者也无法直接获取用户明文密码</li>
 * </ul>
 *
 * <h2>架构说明</h2>
 * <p>
 * 本服务直接使用 {@link UserMapper}（MyBatis-Plus 的 BaseMapper）操作数据库，
 * 没有经过额外的 Repository 抽象层。这是一种务实的架构选择，
 * 在小型项目中可以减少不必要的抽象层级。
 * </p>
 *
 * <h2>认证流程</h2>
 * <ol>
 *   <li><b>注册</b>：验证用户名唯一性 → BCrypt 加密密码 → 写入数据库 → 返回 JWT</li>
 *   <li><b>登录</b>：查询用户是否存在 → 检查账号是否启用 → BCrypt 验证密码 → 返回 JWT</li>
 *   <li><b>后续请求</b>：客户端携带 JWT → 拦截器解析 JWT → 注入租户上下文</li>
 * </ol>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see JwtTokenProvider   JWT 签发与验证
 * @see BCryptPasswordEncoder 密码加密器
 */
@Slf4j
@Service
public class UserService {

    /**
     * MyBatis-Plus 的 UserMapper 接口，提供对 user 表的基础 CRUD 操作。
     * 继承自 {@code BaseMapper<User>}，自动获得 selectOne、selectCount、insert 等方法。
     */
    private final UserMapper userMapper;

    /**
     * 密码编码器，使用 BCrypt 算法进行单向哈希加密。
     * 声明为 {@link PasswordEncoder} 接口类型，便于未来切换加密算法（如 Argon2）。
     * 当前实现为 {@link BCryptPasswordEncoder}，使用默认的 10 轮 log rounds。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * JWT Token 提供者，用于在注册和登录成功后签发 JWT 令牌。
     * 使用 {@link Resource @Resource} 注解进行字段注入（而非构造器注入），
     * 因为 passwordEncoder 是直接在字段上初始化的，无法通过构造器注入。
     */
    @Resource
    private JwtTokenProvider jwtTokenProvider;

    /**
     * 令牌版本服务（V33）：改密/重置密码后 {@code bump} 一次，旧 token 立即失效。
     */
    @Resource
    private TokenVersionService tokenVersionService;

    /**
     * <h3>构造函数 - 依赖注入</h3>
     *
     * @param userMapper MyBatis-Plus 用户 Mapper，用于数据库操作
     */
    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * <h3>用户注册</h3>
     * <p>
     * 处理新用户的注册请求，验证输入合法性后将用户信息写入数据库，
     * 并直接返回一个已签发的 JWT（即"注册即登录"）。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li><b>用户名唯一性检查</b>：查询数据库中是否已存在同名用户，
     *       若存在则抛出异常，防止重复注册</li>
     *   <li><b>默认值填充</b>：若租户 ID 为空则默认 "default"；
     *       若角色为空则默认 "USER"</li>
     *   <li><b>密码加密</b>：使用 BCrypt 对明文密码进行哈希加密后存储，
     *       <strong>绝不存储明文密码</strong></li>
     *   <li><b>写入数据库</b>：通过 MyBatis-Plus 的 insert 方法插入用户记录</li>
     *   <li><b>签发 JWT</b>：立即生成 JWT 返回给客户端，实现"注册即登录"</li>
     * </ol>
     *
     * <h4>安全考量</h4>
     * <ul>
     *   <li>密码经过 BCrypt 加密后才存储，数据库管理员也无法看到明文密码</li>
     *   <li>用户名唯一性检查存在竞态条件风险（两个并发请求同时注册相同用户名），
     *       建议在数据库层面添加 UNIQUE 约束作为最后防线</li>
     *   <li>异常消息"用户名已存在"暴露了用户信息，可能被攻击者用于用户名枚举。
     *       在生产环境中可以考虑使用更模糊的错误消息</li>
     * </ul>
     *
     * @param username 用户名，用于登录标识，需保证唯一性
     * @param password 明文密码，将由 BCrypt 加密后存储（不会保存明文）
     * @param tenantId 租户 ID，用于多租户数据隔离。
     *                 若为 {@code null} 或空字符串，自动使用默认值 "default"
     * @param role     用户角色，如 {@code "USER"} 或 {@code "ADMIN"}。
     *                 若为 {@code null} 或空字符串，自动使用默认值 "USER"
     * @param birthYear 出生年份（V34 合规，必填）：{@code 1900..当前年}；
     *                  未满 18 岁自动置 {@code minor_mode=1}（服务端强制宵禁/每日上限）。
     *                  缺失或越界抛 {@link BizException}(400)
     * @return 签名的 JWT 字符串，客户端可在后续请求中使用此 Token 进行身份认证
     * @throws RuntimeException 当用户名已存在时抛出
     */
    public String register(String username, String password, String tenantId, String role, Integer birthYear) {
        // V34 合规：出生年份必填且范围合法。先校验（客户端错误），再谈唯一性。
        int currentYear = java.time.Year.now(java.time.ZoneId.of("Asia/Shanghai")).getValue();
        if (birthYear == null || birthYear < 1900 || birthYear > currentYear) {
            throw new BizException(400, "出生年份无效");
        }
        if (userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new RuntimeException("用户名已存在");
        }
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";
        // 安全（2026-09-05 高危修复 #1）：公开注册入口一律 USER——请求体 role 不可信，
        // 杜绝注册自封 ADMIN 越权。管理员账号只经受控路径创建（flyway seed / 内部工具）。
        role = "USER";

        User user = new User(username, passwordEncoder.encode(password), tenantId, role);
        user.setBirthYear(birthYear);
        user.setMinorMode(isMinor(birthYear, currentYear));
        userMapper.insert(user);
        log.info("User registered: username={}, tenant={}, minorMode={}", username, tenantId, user.getMinorMode());
        return jwtTokenProvider.generateToken(username, tenantId, role, 0L);   // 新用户版本恒为 0
    }

    /** 出生年份 → 是否未成年人（未满 18 岁）。 */
    static boolean isMinor(int birthYear, int currentYear) {
        return currentYear - birthYear < 18;
    }

    /**
     * <h3>用户登录</h3>
     * <p>
     * 验证用户凭据（用户名 + 密码），验证通过后签发 JWT 令牌。
     * 本方法执行多层安全校验，任何一层失败都会抛出异常。
     * </p>
     *
     * <h4>执行流程（多层校验）</h4>
     * <ol>
     *   <li><b>用户存在性检查</b>：根据用户名查询数据库，
     *       若用户不存在则直接返回"用户名或密码错误"</li>
     *   <li><b>账号启用状态检查</b>：检查 {@code enabled} 字段，
     *       若账号已被管理员禁用则拒绝登录</li>
     *   <li><b>密码验证</b>：使用 BCrypt 的 {@code matches()} 方法
     *       将用户输入的明文密码与数据库中的哈希值进行比对</li>
     *   <li><b>签发 JWT</b>：所有校验通过后生成 JWT 返回给客户端</li>
     * </ol>
     *
     * <h4>安全设计原则</h4>
     * <ul>
     *   <li><b>模糊错误消息</b>：用户不存在和密码错误返回相同的消息
     *       "用户名或密码错误"，防止攻击者通过错误消息差异进行用户名枚举攻击</li>
     *   <li><b>账号禁用</b>：被禁用的账号返回独立错误提示"账号已被禁用"，
     *       让合法用户知晓账号状态，同时不影响安全性（攻击者无法利用此消息枚举用户名，
     *       因为必须先用正确的密码通过第一层校验才能到达此分支）</li>
     *   <li><b>BCrypt 防时序攻击</b>：{@code passwordEncoder.matches()} 内部使用
     *       恒定时间比较（constant-time comparison），防止通过响应时间差异推断密码信息</li>
     * </ul>
     *
     * @param username 用户名
     * @param password 明文密码，将与数据库中的 BCrypt 哈希值进行比对
     * @return 签名的 JWT 字符串，包含用户身份和租户信息
     * @throws RuntimeException 当用户名不存在、密码错误或账号被禁用时抛出
     */
    public String login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) throw new RuntimeException("用户名或密码错误");

        if (!user.getEnabled()) throw new RuntimeException("账号已被禁用");
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        log.info("User logged in: username={}, tenant={}", username, user.getTenantId());
        return jwtTokenProvider.generateToken(user.getUsername(), user.getTenantId(), user.getRole(),
                user.getTokenVersion() == null ? 0L : user.getTokenVersion());
    }

    /**
     * <h3>个人中心资料（V18）</h3>
     * <p>按用户名取完整用户（含昵称/头像/签名），不存在返回 null。</p>
     */
    public User findProfile(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    /**
     * <h3>更新个人资料（V18，V31 增加纪念日）</h3>
     * <p>仅更新传入的非空字段：昵称/头像 emoji/签名/纪念日。全部为空时直接返回（无写入）。</p>
     *
     * <p><b>纪念日（{@code anniversaryDate}）：</b>ISO {@code yyyy-MM-dd} 字符串。
     * 空串或 {@code null} 视为「不修改」；<b>非法日期直接抛 {@link BizException}（400）</b>，
     * 绝不静默忽略——否则用户会以为已经保存成功。</p>
     *
     * @param username        用户名
     * @param nickname        昵称（可空=不修改）
     * @param avatarEmoji     头像 emoji（可空=不修改）
     * @param bio             个性签名（可空=不修改）
     * @param anniversaryDate 纪念日，ISO {@code yyyy-MM-dd}（可空/空串=不修改）
     * @return true=有更新；false=无可更新字段
     * @throws BizException 纪念日格式非法时（code=400）
     */
    public boolean updateProfile(String username, String nickname, String avatarEmoji, String bio,
                                 String anniversaryDate) {
        // 纪念日先解析：格式非法立即报错（在落库前失败，避免写坏数据/误导用户）
        LocalDate parsedAnniversary = null;
        if (anniversaryDate != null && !anniversaryDate.isBlank()) {
            try {
                parsedAnniversary = LocalDate.parse(anniversaryDate.trim());
            } catch (DateTimeParseException e) {
                throw new BizException(400, "纪念日格式不正确，应为 yyyy-MM-dd（如 2023-05-20）");
            }
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        boolean changed = false;
        if (nickname != null && !nickname.isBlank()) {
            String v = nickname.trim();
            user.setNickname(v.length() > 64 ? v.substring(0, 64) : v);
            changed = true;
        }
        if (avatarEmoji != null && !avatarEmoji.isBlank()) {
            String v = avatarEmoji.trim();
            user.setAvatarEmoji(v.length() > 16 ? v.substring(0, 16) : v);
            changed = true;
        }
        if (bio != null && !bio.isBlank()) {
            String v = bio.trim();
            user.setBio(v.length() > 200 ? v.substring(0, 200) : v);
            changed = true;
        }
        if (parsedAnniversary != null) {
            user.setAnniversaryDate(parsedAnniversary);
            changed = true;
        }
        if (changed) {
            userMapper.updateById(user);
        }
        return changed;
    }

    /**
     * <h3>修改密码（V18；V33 起旧 token 立即失效）</h3>
     * <p>校验旧密码（BCrypt matches）→ 新密码长度 ≥ 8 且不同于旧密码 → 更新为 BCrypt 密文
     * → <b>令牌版本 +1</b>（改密后所有已签发 token 立刻 401，防止被盗 token 继续用满 24 小时）。</p>
     */
    public void changePassword(String username, String oldPassword, String newPassword) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("旧密码不正确");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new RuntimeException("新密码长度至少 8 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new RuntimeException("新密码不能与旧密码相同");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        tokenVersionService.bump(username);   // V33：撤销旧 token
        log.info("User changed password: username={}", username);
    }

    /**
     * <h3>按用户名取用户（V33）</h3>
     * <p>不存在返回 {@code null}（调用方自行决定是报错还是静默——找回密码走静默）。</p>
     */
    public User findByUsername(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    /**
     * <h3>邮箱是否已被其他账号占用（V33）</h3>
     * <p>数据库有唯一索引兜底，这里做前置检查是为了给出可读提示（而非 409 数据冲突）。</p>
     *
     * @param email          已归一化（小写/去空白）的邮箱
     * @param exceptUsername 允许占用该邮箱的账号（通常是当前用户自己）
     */
    public boolean emailTaken(String email, String exceptUsername) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)
                .ne(User::getUsername, exceptUsername));
        return count != null && count > 0;
    }

    /**
     * <h3>绑定邮箱（V33）</h3>
     * <p>写入 {@code email} 与 {@code email_verified_at}（调用方必须已校验验证码——
     * 本方法不做验证码校验，只落库）。</p>
     *
     * @return 归一化后的邮箱
     */
    public String bindEmail(String username, String email) {
        User user = findByUsername(username);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        user.setEmail(email);
        user.setEmailVerifiedAt(java.time.LocalDateTime.now());
        userMapper.updateById(user);
        log.info("User bound email: username={}, email={}", username, maskEmail(email));
        return email;
    }

    /**
     * <h3>重置密码（V33 忘记密码）</h3>
     * <p>与 {@link #changePassword} 的区别：<b>不需要旧密码</b>（调用方已用邮箱验证码证明身份）。
     * 同样做长度校验并 <b>令牌版本 +1</b>，让所有旧 token（包括攻击者手里那个）立即失效。</p>
     */
    public void resetPassword(String username, String newPassword) {
        User user = findByUsername(username);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new BizException(400, "新密码长度至少 8 位");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BizException(400, "新密码不能与当前密码相同");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        tokenVersionService.bump(username);   // V33：撤销旧 token（找回的安全底线）
        log.info("User password reset: username={}", username);
    }

    /**
     * <h3>更新进化模块单独同意（V34 合规，docs/07 §5.4）</h3>
     * <p>写 {@code users.evolution_consent}。这是<b>隐私开关</b>：只有同意后，
     * {@code ReflectionScheduler} 才会把该用户的会话提炼成技能沉淀进知识库。</p>
     *
     * @param username 用户名
     * @param consent  true=同意，false=撤回同意
     * @return 落库后的值
     * @throws BizException 用户不存在时（400）
     */
    public boolean updateEvolutionConsent(String username, boolean consent) {
        User user = findByUsername(username);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        user.setEvolutionConsent(consent);
        userMapper.updateById(user);
        log.info("User evolution consent updated: username={}, consent={}", username, consent);
        return consent;
    }

    /**
     * 标记「首次体验引导已看过」（V36，ADR-91）。幂等。
     *
     * <p>⛔ 只有"置为已看过"一个方向：没有 un-onboard 接口 —— 用户想看回来点页面上的
     * 「能做什么」按钮即可（纯前端开关），不需要服务端状态回退。</p>
     */
    public void markOnboarded(String username) {
        User user = findByUsername(username);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        if (Boolean.TRUE.equals(user.getOnboarded())) {
            return;   // 幂等：已经是了就不写库
        }
        user.setOnboarded(true);
        userMapper.updateById(user);
        log.info("User onboarded flag set: username={}", username);
    }

    /**
     * 设置回访邮件的同意状态（V37，ADR-92）。幂等。
     *
     * <p>⛔ 只改同意位，不发信、不验证邮箱 —— 资格判定在 {@code ReengageService} 里统一做。</p>
     */
    public void updateReengageConsent(String username, boolean consent) {
        User user = findByUsername(username);
        if (user == null) {
            throw new BizException(400, "用户不存在");
        }
        if (Boolean.valueOf(consent).equals(user.getReengageConsent())) {
            return;   // 幂等：没变就不写库
        }
        user.setReengageConsent(consent);
        userMapper.updateById(user);
        log.info("User reengage consent updated: username={}, consent={}", username, consent);
    }

    /** 退订（回到同意位 = false）。失败方向安全：只可能少发，不可能多发。 */
    public void unsubscribeReengage(String username) {
        updateReengageConsent(username, false);
    }

    /** 日志里不打完整邮箱（PII 最小化）。 */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
