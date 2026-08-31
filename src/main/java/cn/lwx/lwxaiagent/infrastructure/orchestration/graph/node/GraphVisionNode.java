package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.constant.FileConstant;
import cn.lwx.lwxaiagent.entity.MessageMedia;
import cn.lwx.lwxaiagent.infrastructure.ai.VisionPort;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视觉对话节点（ADR-11，入口收敛后补回）：mediaIds → 图片字节 + 用户消息 → VisionPort。
 * <p>归属校验：图片属于当前用户否则拒绝；支持多图（≤4，上传侧已限）。</p>
 */
@Slf4j
@Component
public class GraphVisionNode {

    private final MessageMediaMapper mediaMapper;
    private final VisionPort visionPort;

    public GraphVisionNode(MessageMediaMapper mediaMapper, VisionPort visionPort) {
        this.mediaMapper = mediaMapper;
        this.visionPort = visionPort;
    }

    public Map<String, Object> apply(OverAllState state) {
        String userId = state.value(GraphStateKeys.USER_ID).map(Object::toString).orElse("anonymous");
        String message = state.value(GraphStateKeys.MESSAGE).map(Object::toString).orElse("");
        Object mediaObj = state.value(GraphStateKeys.MEDIA_IDS).orElse(null);
        List<Long> mediaIds = new ArrayList<>();
        if (mediaObj instanceof List<?> l) {
            for (Object o : l) if (o instanceof Number n) mediaIds.add(n.longValue());
        }
        Map<String, Object> out = new HashMap<>();
        if (mediaIds.isEmpty()) {
            out.put(GraphStateKeys.OUTPUT, "没有收到图片，请重新上传后提问。");
            return out;
        }

        List<byte[]> images = new ArrayList<>();
        String mime = "image/jpeg";
        for (Long id : mediaIds) {
            MessageMedia media = mediaMapper.selectById(id);
            if (media == null || !userId.equals(media.getUserId())) {
                out.put(GraphStateKeys.OUTPUT, "无权访问这张图片，请联系对方在原会话中查看。");
                return out;
            }
            try {
                images.add(Files.readAllBytes(Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", media.getObjectKey())));
                if ("PNG".equals(media.getMediaType())) mime = "image/png";
                else if ("WEBP".equals(media.getMediaType())) mime = "image/webp";
            } catch (Exception e) {
                log.warn("GraphVisionNode load media failed ({}): {}", id, e.getMessage());
            }
        }
        if (images.isEmpty()) {
            out.put(GraphStateKeys.OUTPUT, "图片读取失败，请稍后再试。");
            return out;
        }
        try {
            String prompt = "请结合用户文字与图片内容作答。用户说：" + message;
            String text = visionPort.chat(prompt, images, mime);
            out.put(GraphStateKeys.OUTPUT, text);
        } catch (Exception e) {
            log.error("GraphVisionNode vision call failed: {}", e.getMessage());
            out.put(GraphStateKeys.OUTPUT, "图片理解暂时不可用，请稍后再试。");
        }
        return out;
    }
}