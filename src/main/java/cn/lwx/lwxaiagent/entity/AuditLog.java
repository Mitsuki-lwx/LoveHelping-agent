package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 审计日志实体（V20，2026-09-07 课3）。
 * <p>append-only：只 insert，不 update/delete。五元组设计：
 * actor(谁)·action(做了什么)·resource(对什么)·result(结果)·createdAt(何时)。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 谁：userId；未登录=anonymous */
    private String actor;

    /** 动作：login / change_password / delete_account / sandbox_delete / memory_update ... */
    private String action;

    /** 对什么：HTTP URI（如 /auth/password） */
    private String resource;

    /** SUCCESS / FAIL */
    private String result;

    /** 附加信息（失败原因、关键上下文等），限长 500 */
    private String detail;

    private String ip;

    @TableField("created_at")
    private java.time.LocalDateTime createdAt;
}
