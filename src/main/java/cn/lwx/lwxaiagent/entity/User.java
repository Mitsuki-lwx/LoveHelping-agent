package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

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
