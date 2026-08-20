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
     * 创建时间，对应数据库列 {@code created_at}。
     * <p>记录该用户账号的注册/创建时间戳。</p>
     */
    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

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
