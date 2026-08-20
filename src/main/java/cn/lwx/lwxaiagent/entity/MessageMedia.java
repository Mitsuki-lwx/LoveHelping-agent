package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息图片附件（ADR-11）。图片存本地 uploads 目录（object_key），数据库只存元数据。
 */
@Data
@TableName("message_media")
public class MessageMedia {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    /** JPG / PNG / WEBP */
    @TableField("media_type")
    private String mediaType;

    /** 本地文件路径（相对 uploads 目录） */
    @TableField("object_key")
    private String objectKey;

    @TableField("width")
    private Integer width;

    @TableField("height")
    private Integer height;

    @TableField("content_hmac")
    private String contentHmac;

    /** PENDING（未引用）/ USED（已被聊天引用） */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
