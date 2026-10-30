package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.constant.FileConstant;
import cn.lwx.lwxaiagent.entity.MessageMedia;
import cn.lwx.lwxaiagent.infrastructure.ai.VisionPort;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import cn.lwx.lwxaiagent.service.SandboxService;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 沙盘看图（2026-10-07）。
 *
 * <p>为什么这条必须永久守：路由 {@code selectRoute} **先判 SANDBOX_ID → R_SANDBOX**，
 * 早于 {@code hasMedia → R_VISION} —— 也就是说视觉**不可能**靠路由走进沙盘。
 * 如果本节点不自己读 MEDIA_IDS，那"沙盘发图"就是**假的**：图上传成功、mediaIds 也进了状态，
 * 但模型根本看不到，用户会以为图没发出去。这种"看着成功其实没生效"最难发现。</p>
 *
 * <p>另外两条同样重要：<b>人设不能丢</b>（带图也要用沙盘 prompt）、<b>跨用户读图必须拒</b>（越权面）。</p>
 */
class SandboxChatNodeVisionTest {

    private OverAllState stateWith(String message, Object mediaIds) {
        Map<String, Object> m = new HashMap<>();
        m.put(GraphStateKeys.MESSAGE, message);
        m.put(GraphStateKeys.CHAT_ID, "c1");
        m.put(GraphStateKeys.USER_ID, "u1");
        m.put(GraphStateKeys.SANDBOX_ID, 9L);
        if (mediaIds != null) m.put(GraphStateKeys.MEDIA_IDS, mediaIds);
        return new OverAllState(m);
    }

    @Test
    void 带图时必须走视觉_且人设prompt不能丢() throws Exception {
        SandboxService sandbox = mock(SandboxService.class);
        ChatExecutor exec = mock(ChatExecutor.class);
        VisionPort vision = mock(VisionPort.class);
        MessageMediaMapper mapper = mock(MessageMediaMapper.class);

        // ⛔ FileConstant.FILE_SAVE_DIR 是**编译期常量**（interface 里的 static final，取自 user.dir），
        //    反射改不动（实测 IllegalAccessException）。照本仓既有先例（MediaControllerTest）：
        //    **在真实根目录下写真文件、用完删掉** —— 真读 IO，不 mock 文件系统。
        String key = "u1-test/" + java.util.UUID.randomUUID() + ".jpg";
        Path real = Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", key);
        try {
            Files.createDirectories(real.getParent());
            Files.write(real, "fake-bytes".getBytes());

            when(sandbox.buildSandboxPrompt(9L, "u1")).thenReturn("【人设】小傲娇");
            MessageMedia mm = new MessageMedia();
            mm.setId(7L); mm.setUserId("u1"); mm.setMediaType("JPG"); mm.setObjectKey(key);
            when(mapper.selectById(7L)).thenReturn(mm);
            when(vision.chat(anyString(), anyList(), anyString())).thenReturn("（小傲娇）哼，这照片拍得还行吧。");

            var node = new SandboxChatNode(sandbox, exec, vision, mapper);
            Map<String, Object> out = node.apply(stateWith("看看这张", List.of(7)));

            assertEquals("（小傲娇）哼，这照片拍得还行吧。", out.get(GraphStateKeys.OUTPUT));
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(vision).chat(captor.capture(), anyList(), eq("image/jpeg"));
            assertTrue(captor.getValue().contains("小傲娇"), "⛔ 人设 prompt 必须带进视觉调用");
            assertTrue(captor.getValue().contains("看看这张"), "用户文字也要带上");
            // 走视觉时**不能**再去调文本链（否则同一句话被答两遍）
            verifyNoInteractions(exec);
        } finally {
            Files.deleteIfExists(real);
        }
    }

    @Test
    void 跨用户读图必须拒绝_不能借沙盘读别人的图() throws Exception {
        SandboxService sandbox = mock(SandboxService.class);
        VisionPort vision = mock(VisionPort.class);
        MessageMediaMapper mapper = mock(MessageMediaMapper.class);
        when(sandbox.buildSandboxPrompt(anyLong(), anyString())).thenReturn("p");
        MessageMedia other = new MessageMedia();
        other.setId(7L); other.setUserId("someone-else"); other.setObjectKey("x/y.jpg");
        when(mapper.selectById(7L)).thenReturn(other);

        var node = new SandboxChatNode(sandbox, mock(ChatExecutor.class), vision, mapper);
        Map<String, Object> out = node.apply(stateWith("看看", List.of(7)));

        assertTrue(String.valueOf(out.get(GraphStateKeys.OUTPUT)).contains("无权"));
        verifyNoInteractions(vision);            // 连调用都不该发生
    }

    @Test
    void 不带图时行为与以前完全一致_走文本链() throws Exception {
        SandboxService sandbox = mock(SandboxService.class);
        ChatExecutor exec = mock(ChatExecutor.class);
        VisionPort vision = mock(VisionPort.class);
        when(sandbox.buildSandboxPrompt(9L, "u1")).thenReturn("p");
        when(exec.executeWithRag(anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult.ShallowResult(
                        reactor.core.publisher.Flux.just("文本回答")));

        var node = new SandboxChatNode(sandbox, exec, vision, mock(MessageMediaMapper.class));
        Map<String, Object> out = node.apply(stateWith("随便聊聊", null));

        assertEquals("文本回答", out.get(GraphStateKeys.OUTPUT));
        verifyNoInteractions(vision);
    }
}
