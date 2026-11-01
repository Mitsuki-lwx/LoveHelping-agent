package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult;
import cn.lwx.lwxaiagent.entity.MessageMedia;
import cn.lwx.lwxaiagent.infrastructure.ai.VisionPort;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import cn.lwx.lwxaiagent.constant.FileConstant;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.service.SandboxService;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 沙盘对话节点（ADR-19 CAP-8/CAP-9）：人设 prompt + 沙盘记忆 + 动态情绪 + RAG。
 * <p>通过 {@link SandboxService#buildSandboxPrompt} 组装沙盘人格上下文，
 * 走 {@link ChatExecutor#executeWithRag}（沙盘同频知识库检索增强）。</p>
 */
@Slf4j
@Component
public class SandboxChatNode {

    private final SandboxService sandboxService;
    private final ChatExecutor chatExecutor;
    /** 沙盘看图（2026-10-07）：路由先判 SANDBOX_ID ⇒ 永远走不到 R_VISION，视觉必须在本节点内处理。 */
    private final VisionPort visionPort;
    private final MessageMediaMapper mediaMapper;

    public SandboxChatNode(SandboxService sandboxService, ChatExecutor chatExecutor,
                           VisionPort visionPort, MessageMediaMapper mediaMapper) {
        this.sandboxService = sandboxService;
        this.chatExecutor = chatExecutor;
        this.visionPort = visionPort;
        this.mediaMapper = mediaMapper;
    }

    public Map<String, Object> apply(OverAllState state) {
        String message = state.value(GraphStateKeys.MESSAGE).map(Object::toString).orElse("");
        String chatId = state.value(GraphStateKeys.CHAT_ID).map(Object::toString).orElse("anon");
        String userId = state.value(GraphStateKeys.USER_ID).map(Object::toString).orElse("anonymous");
        long sandboxId = state.value(GraphStateKeys.SANDBOX_ID)
                .map(v -> ((Number) v).longValue()).orElse(-1L);

        if (sandboxId <= 0) {
            log.warn("SandboxChatNode without valid sandboxId, fallback to plain: {}", sandboxId);
        }
        String sandboxPrompt = sandboxService.buildSandboxPrompt(sandboxId, userId);

        // ⛔ 2026-10-07：**沙盘也要能看图**。路由 `selectRoute` 先判 SANDBOX_ID → R_SANDBOX（早于 hasMedia → R_VISION），
        //    所以视觉**不可能**靠路由走到 —— 必须在本节点内处理。否则"沙盘发图"是**假的**：
        //    图上传成功、mediaIds 也进了状态，但模型根本看不到（用户会以为图没发出去）。
        //    人设 prompt 照旧带上，只是把图片一起交给视觉模型 —— **不能丢人设**。
        List<Long> mediaIds = readMediaIds(state);
        if (!mediaIds.isEmpty()) {
            return Map.of(GraphStateKeys.OUTPUT, answerWithImages(message, sandboxPrompt, userId, mediaIds));
        }

        AgentResult.ShallowResult sr = chatExecutor.executeWithRag(message, chatId, sandboxPrompt, false);
        String full = sr.flux().reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString).block();
        // Failures must propagate so pipeline traces and task state cannot report false success.
        return Map.of(GraphStateKeys.OUTPUT, full == null ? "" : full);
    }

    /** 图状态里的 mediaIds（与 GraphVisionNode 同口径：只认 Number）。 */
    private List<Long> readMediaIds(OverAllState state) {
        Object mediaObj = state.value(GraphStateKeys.MEDIA_IDS).orElse(null);
        List<Long> ids = new ArrayList<>();
        if (mediaObj instanceof List<?> l) {
            for (Object o : l) if (o instanceof Number n) ids.add(n.longValue());
        }
        return ids;
    }

    /**
     * 带图回答：人设 prompt + 用户文字 + 图片 → 视觉模型。
     * ⛔ 归属校验照抄 {@code GraphVisionNode}：**跨用户读图必须拒绝**（越权面，docs/07 §3）。
     */
    private String answerWithImages(String message, String sandboxPrompt, String userId, List<Long> mediaIds) {
        List<byte[]> images = new ArrayList<>();
        String mime = "image/jpeg";
        for (Long id : mediaIds) {
            MessageMedia media = mediaMapper.selectById(id);
            if (cn.lwx.lwxaiagent.tenant.Ownership.requireOrNull(userId, media, m -> m.getUserId()) == null) {
                return "无权访问这张图片。";
            }
            try {
                images.add(Files.readAllBytes(Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", media.getObjectKey())));
                if ("PNG".equals(media.getMediaType())) mime = "image/png";
                else if ("WEBP".equals(media.getMediaType())) mime = "image/webp";
            } catch (Exception e) {
                log.warn("SandboxChatNode load media failed ({}): {}", id, e.getMessage());
            }
        }
        if (images.isEmpty()) {
            return "图片读取失败，请稍后再试。";
        }
        try {
            // 人设在前、图片任务在后：模型要**以这个人设**回应图里的内容
            String prompt = sandboxPrompt + "\n\n（用户发来了图片，请按你的人设回应）用户说：" + message;
            return visionPort.chat(prompt, images, mime);
        } catch (Exception e) {
            log.error("SandboxChatNode vision call failed: {}", e.getMessage());
            return "图片理解暂时不可用，请稍后再试。";
        }
    }
}