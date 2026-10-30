package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <h1>投诉举报（V34 合规）—— 对应数据库表 {@code complaint}</h1>
 *
 * <p>用户对内容/隐私/辱骂等问题的投诉留证。四分类 {@code category}、
 * 三状态 {@code status}（OPEN/PROCESSING/CLOSED）的取值见
 * {@link cn.lwx.lwxaiagent.service.ComplaintService} 的常量。</p>
 *
 * <p><b>不随用户注销而删除</b>：投诉是合规留证，{@code user_id} 只是标识谁提交的，
 * 刻意不建外键——注销走软删 + 物理清除（ADR-5），两者不该互相牵制。</p>
 *
 * @see cn.lwx.lwxaiagent.mapper.ComplaintMapper
 */
@Data
@TableName("complaint")
public class Complaint {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private String tenantId;

    /** 提交人（= users.username）。 */
    @TableField("user_id")
    private String userId;

    /** CONTENT / PRIVACY / ABUSE / OTHER。 */
    private String category;

    /** 投诉正文（≤1000 字）。 */
    private String content;

    /** 联系方式，可空。 */
    private String contact;

    /** OPEN / PROCESSING / CLOSED。 */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
