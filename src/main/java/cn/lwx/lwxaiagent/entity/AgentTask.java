package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 任务（ADR-3）：生命周期落库，崩溃可补偿。
 * 状态机：PENDING → RUNNING → SUCCESS / FAILED / CANCELLED。
 */
@Data
@TableName("agent_task")
public class AgentTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("user_id")
    private String userId;

    @TableField("instruction")
    private String instruction;

    @TableField("status")
    private String status;

    @TableField("result_ref")
    private String resultRef;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("token_usage")
    private Long tokenUsage;

    @TableField("heartbeat_at")
    private LocalDateTime heartbeatAt;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
