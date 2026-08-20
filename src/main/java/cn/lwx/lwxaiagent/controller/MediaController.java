package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.constant.FileConstant;
import cn.lwx.lwxaiagent.entity.MessageMedia;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 图片上传接口（ADR-11）。
 * <p>
 * 限制：JPG/PNG/WebP、原始 ≤10MB、每次 ≤4 张（前端约束 + 服务端校验）。
 * 图片存本地 {@code uploads/{userId}/} 目录，数据库只存元数据（04 §2.7）。
 * 归属：只能上传/使用自己的图片（JWT 校验）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/media")
public class MediaController {

    private static final Set<String> ALLOWED_TYPES = Set.of("jpg", "jpeg", "png", "webp");
    private static final long MAX_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_PER_REQUEST = 4;

    private final MessageMediaMapper mediaMapper;

    public MediaController(MessageMediaMapper mediaMapper) {
        this.mediaMapper = mediaMapper;
    }

    @PostMapping("/upload")
    public Result<MessageMedia> upload(@RequestParam("file") MultipartFile file) {
        String userId = requireUserId();
        if (file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BizException(400, "图片过大（最大 10MB）");
        }
        String ext = extractExt(file.getOriginalFilename());
        if (!ALLOWED_TYPES.contains(ext)) {
            throw new BizException(400, "仅支持 JPG/PNG/WebP");
        }

        try {
            // 读取尺寸（同时校验是否为有效图片）
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new BizException(400, "无法识别的图片文件");
            }
            int width = image.getWidth();
            int height = image.getHeight();
            // 服务端压缩上限：长边超过 2048px 拒绝（视觉 token 昂贵，ADR-11）
            if (Math.max(width, height) > 2048) {
                throw new BizException(400, "图片过长边超过 2048px，请压缩后上传");
            }

            // 存储：uploads/{userId}/{uuid}.{ext}
            String dir = FileConstant.FILE_SAVE_DIR + File.separator + "uploads" + File.separator + userId;
            Files.createDirectories(Paths.get(dir));
            String objectKey = userId + File.separator + UUID.randomUUID() + "." + ext;
            Path target = Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", objectKey);
            Files.copy(file.getInputStream(), target);

            MessageMedia media = new MessageMedia();
            media.setUserId(userId);
            media.setMediaType(ext.equals("jpeg") ? "JPG" : ext.toUpperCase());
            media.setObjectKey(objectKey);
            media.setWidth(width);
            media.setHeight(height);
            media.setStatus("PENDING");
            media.setCreatedAt(LocalDateTime.now());
            mediaMapper.insert(media);

            log.info("Media uploaded: id={} user={} type={} {}x{}", media.getId(), userId, ext, width, height);
            return Result.ok(media);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Media upload failed: {}", e.getMessage());
            throw new BizException(500, "上传失败，请稍后重试");
        }
    }

    private String requireUserId() {
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }

    private String extractExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
