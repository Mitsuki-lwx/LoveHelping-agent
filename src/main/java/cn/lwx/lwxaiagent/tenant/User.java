package cn.lwx.lwxaiagent.tenant;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 用户实体（MyBatis-Plus）。
 * <p>
 * 一个用户属于一个租户（tenantId），一个租户有多个用户。
 * 同一租户内区分管理员（ADMIN）和普通用户（USER）。
 * <p>
 * 表结构：users
 * 字段：
 * - id          主键自动递增
 * - username    用户名（唯一）
 * - password    BCrypt 加密后的密码
 * - tenant_id   所属租户 ID，默认 "default"
 * - role        角色：USER（普通用户）| ADMIN（管理员）
 * - enabled     是否启用
 * - created_at  创建时间
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String password;

    @TableField("tenant_id")
    private String tenantId;

    private String role;

    private Boolean enabled;

    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

    public User() {}

    public User(String username, String password, String tenantId, String role) {
        this.username = username;
        this.password = password;
        this.tenantId = tenantId;
        this.role = role;
        this.enabled = true;
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }

    public boolean isUser() {
        return "USER".equalsIgnoreCase(role);
    }
}
