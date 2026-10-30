package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * <p>
 * 用户实体类 —— 对应数据库表 <strong>users</strong>。
 * </p>
 *
 * <p>
 * <strong>核心作用：</strong>该类用于存储系统用户的基本账号信息，包括登录凭据、
 * 所属租户、角色权限和账号状态。是系统认证和授权的核心数据模型。
 * </p>
 *
 * <p>
 * <strong>多租户设计：</strong>每个用户通过 {@code tenantId} 字段关联到一个租户。
 * 同一租户下可以有多个用户（如管理员和普通成员），不同租户的用户数据完全隔离。
 * 这种设计支持 SaaS（Software as a Service）多租户架构。
 * </p>
 *
 * <p>
 * <strong>角色权限说明（{@code role} 字段）：</strong>
 * </p>
 * <ul>
 *   <li><strong>ADMIN：</strong>管理员角色，拥有系统的全部管理权限，
 *       例如管理同租户下的其他用户、查看所有会话记录、配置系统参数等。</li>
 *   <li><strong>USER：</strong>普通用户角色，拥有基本的对话交互权限，
 *       可以创建会话、与 AI 对话、对自己的对话执行反思等。</li>
 * </ul>
 *
 * <p>
 * <strong>账号状态：</strong>{@code enabled} 字段控制账号的启用/禁用状态。
 * 当用户被禁用时（{@code enabled = false}），将无法登录或使用系统功能。
 * </p>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.mapper.UserMapper
 */
@Data
@TableName("users")
public class User {

    /**
     * 主键 ID，数据库自增（AUTO_INCREMENT）。
     * <p>使用 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.annotation.IdType#AUTO} 策略，
     * 由数据库自动生成唯一标识。</p>
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名，对应数据库列 {@code username}。
     * <p>用户在系统中的唯一标识名称，用于登录认证。
     * 在同一租户下，用户名应当保持唯一（通常在业务层或数据库层做唯一约束）。</p>
     */
    private String username;

    /**
     * 密码，对应数据库列 {@code password}。
     * <p>用户登录密码，<strong>必须经过加密（哈希）处理后存储</strong>（如 BCrypt）。
     * 数据库中永远不应存储明文密码。Spring Security 等安全框架会自动处理密码编码。</p>
     */
    private String password;

    /**
     * 租户 ID，对应数据库列 {@code tenant_id}。
     * <p>标识该用户所属的租户（组织/团队）。用于多租户数据隔离，
     * 确保用户只能访问自己租户下的数据（会话、技能、知识等）。</p>
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 角色，对应数据库列 {@code role}。
     * <p>标识用户的权限角色，取值范围：</p>
     * <ul>
     *   <li>{@code "ADMIN"} —— 管理员，拥有最高权限</li>
     *   <li>{@code "USER"} —— 普通用户，拥有基本操作权限</li>
     * </ul>
     * <p>角色判断推荐使用 {@link #isAdmin()} 和 {@link #isUser()} 方法，
     * 而不是直接进行字符串比较。</p>
     */
    private String role;

    /**
     * 是否启用（账号状态），对应数据库列 {@code enabled}。
     * <p>{@code true} 表示账号处于启用状态，用户可以正常登录和使用系统；
     * {@code false} 表示账号已被禁用，用户无法登录。
     * 新创建的用户默认为启用状态（{@code true}）。</p>
     */
    private Boolean enabled;

    /**
     * 昵称（V18 个人中心），对应数据库列 {@code nickname}。
     * <p>个人中心的展示名，可空——空时前端/后端兜底显示用户名。</p>
     */
    private String nickname;

    /**
     * 头像 emoji（V18 个人中心），对应数据库列 {@code avatar_emoji}。
     * <p>轻量头像方案：emoji 字符（如 ❤️/🌙/🌸），空时兜底用户名首字符。</p>
     */
    @TableField("avatar_emoji")
    private String avatarEmoji;

    /**
     * 个性签名（V18 个人中心），对应数据库列 {@code bio}。
     * <p>一句话自我介绍，展示在个人中心。</p>
     */
    private String bio;

    /**
     * 纪念日（V31 首页「在一起第 N 天」），对应数据库列 {@code anniversary_date}。
     * <p>「在一起」的起始日期，属于用户的关系事实。可空——空表示用户尚未设置，
     * 前端首页不展示「第 N 天」。只存日期（{@link java.time.LocalDate}），不含时间与时区。</p>
     */
    @TableField("anniversary_date")
    private java.time.LocalDate anniversaryDate;

    /**
     * 创建时间，对应数据库列 {@code created_at}。
     * <p>记录该用户账号的注册/创建时间戳。</p>
     */
    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

    /**
     * 绑定邮箱（V33 找回密码），对应数据库列 {@code email}。
     * <p>唯一索引（MySQL 允许多个 NULL）；空 = 未绑定，老用户不受影响。
     * 只有 {@link #emailVerifiedAt} 非空（= 验证码校验通过）才可用于找回密码。</p>
     */
    private String email;

    /**
     * 邮箱验证通过时间（V33），对应数据库列 {@code email_verified_at}。
     * <p>空 = 邮箱未验证；非空 = 该邮箱确属本人（验证码校验通过后写入）。</p>
     */
    @TableField("email_verified_at")
    private java.time.LocalDateTime emailVerifiedAt;

    /**
     * 出生年份（V34 合规），对应数据库列 {@code birth_year}。
     * <p>注册时必填（1900..当前年）；老数据为空 = 未知，按非未成年人处理。</p>
     */
    @TableField("birth_year")
    private Integer birthYear;

    /**
     * 未成年人模式（V34 合规），对应数据库列 {@code minor_mode}。
     * <p>注册时按 {@link #birthYear} 判定（未满 18 ⇒ {@code true}）。服务端据此在
     * 聊天入口强制宵禁与每日条数上限——<b>合规要求，不依赖前端声明</b>。</p>
     */
    @TableField("minor_mode")
    private Boolean minorMode;

    /**
     * 进化模块单独同意（V34 合规），对应数据库列 {@code evolution_consent}。
     * <p>docs/07 §5.4：只有用户<b>明确同意</b>后，其对话才会被提炼为技能沉淀进知识库。
     * <b>默认 {@code false}</b>（隐私默认必须是"不同意"）；{@code ReflectionScheduler}
     * 只处理已同意用户的会话，查不到归属的会话一律跳过。</p>
     */
    @TableField("evolution_consent")
    private Boolean evolutionConsent;

    /** 首次体验引导是否已看过（V36，ADR-91）。false/null = 还没看过 ⇒ 聊天页显示引导卡。 */
    private Boolean onboarded;

    /** 是否同意接收回访邮件（V37，ADR-92）。默认 false —— 触达类同意必须显式给予。 */
    private Boolean reengageConsent;

    /**
     * 令牌版本（V33），对应数据库列 {@code token_version}。
     * <p>签发 JWT 时写入 {@code tv} 声明；改密/重置密码/注销账号时 +1，
     * 使旧 token 立即失效（JWT 无黑名单，靠版本号撤销）。默认 0。</p>
     */
    @TableField("token_version")
    private Integer tokenVersion;

    /**
     * 无参构造函数。
     * <p>MyBatis 和 JSON 反序列化框架（如 Jackson）需要无参构造函数来实例化对象。</p>
     */
    public User() {}

    /**
     * 全参构造函数（不含 ID 和时间戳字段）。
     * <p>用于在代码中便捷地创建新用户。ID 由数据库自增生成，
     * {@code createdAt} 由数据库自动填充，{@code enabled} 默认为 {@code true}。</p>
     *
     * @param username 用户名，用于登录的唯一标识
     * @param password 密码（应为加密后的密文，非明文）
     * @param tenantId 租户 ID，标识用户归属的租户
     * @param role     角色（"ADMIN" 或 "USER"）
     */
    public User(String username, String password, String tenantId, String role) {
        this.username = username;
        this.password = password;
        this.tenantId = tenantId;
        this.role = role;
        this.enabled = true;
    }

    /**
     * 判断当前用户是否为管理员角色。
     * <p>不区分大小写比较，{@code "admin"}、{@code "Admin"}、{@code "ADMIN"} 均视为管理员。</p>
     *
     * @return {@code true} 如果用户角色为 ADMIN（不区分大小写），否则 {@code false}
     */
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    /**
     * 判断当前用户是否为普通用户角色。
     * <p>不区分大小写比较，{@code "user"}、{@code "User"}、{@code "USER"} 均视为普通用户。</p>
     *
     * @return {@code true} 如果用户角色为 USER（不区分大小写），否则 {@code false}
     */
    public boolean isUser() {
        return "USER".equalsIgnoreCase(role);
    }
}
