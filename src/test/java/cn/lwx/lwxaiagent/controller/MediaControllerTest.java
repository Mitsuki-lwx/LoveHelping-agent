package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.constant.FileConstant;
import cn.lwx.lwxaiagent.entity.MessageMedia;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MediaController 读取接口契约测试（ADR-11 图片回显）。
 * <p>核心是越权面：{@code message_media.user_id} 与当前登录用户不符时<b>必须</b>拒绝，
 * 不是"查得到就返回"。另外钉死 404（不存在 / 文件已丢）不得变成 500。</p>
 */
@WebMvcTest(MediaController.class)
class MediaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageMediaMapper mediaMapper;
    @MockBean
    private JwtTokenProvider jwtTokenProvider;
    /** V33：TenantInterceptor 依赖令牌版本服务（拦截器随 SecurityConfig 载入 MVC 切片）。 */
    @MockBean
    private cn.lwx.lwxaiagent.service.TokenVersionService tokenVersionService;

    private static final String OWNER = "u1";
    private static final String OTHER = "u2";
    /** 唯一子目录，避免与真实 uploads 数据或其他测试相互干扰。 */
    private static final String SUB = "_test_media_" + java.util.UUID.randomUUID();

    private static byte[] payload() {
        return "fake-png-bytes".getBytes(StandardCharsets.UTF_8);
    }

    /** 在磁盘上放一张属于 {@code owner} 的图，返回可直接喂给 mapper 的元数据（objectKey 相对 uploads 目录）。 */
    private static MessageMedia storedMedia(long id, String owner, String ext) throws Exception {
        String objectKey = SUB + "/" + owner + "/" + id + "." + ext;
        Path file = Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", objectKey);
        Files.createDirectories(file.getParent());
        Files.write(file, payload());
        MessageMedia media = new MessageMedia();
        media.setId(id);
        media.setUserId(owner);
        media.setMediaType(ext.toUpperCase());
        media.setObjectKey(objectKey);
        media.setStatus("USED");
        return media;
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        Path dir = Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", SUB);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void owner_canReadOwnImageWithCorrectTypeAndCacheHeaders() throws Exception {
        TenantContext.set("default", OWNER, "USER");
        when(mediaMapper.selectById(7L)).thenReturn(storedMedia(7L, OWNER, "png"));

        mockMvc.perform(get("/media/7"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(payload()))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    void jpegMedia_returnsImageJpeg() throws Exception {
        TenantContext.set("default", OWNER, "USER");
        when(mediaMapper.selectById(8L)).thenReturn(storedMedia(8L, OWNER, "jpg"));

        mockMvc.perform(get("/media/8"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    /** 越权核心面：他人图片必须 403，且响应体里不得带图片字节。 */
    @Test
    void otherUser_cannotReadImage_returns403() throws Exception {
        TenantContext.set("default", OTHER, "USER");
        when(mediaMapper.selectById(7L)).thenReturn(storedMedia(7L, OWNER, "png"));

        var response = mockMvc.perform(get("/media/7"))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权访问这张图片"))
                .andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertFalse(
                new String(response.getContentAsByteArray(), StandardCharsets.UTF_8).contains("fake-png-bytes"),
                "越权响应绝不能回图片内容");
    }

    @Test
    void unknownId_returns404NotServerError() throws Exception {
        TenantContext.set("default", OWNER, "USER");
        when(mediaMapper.selectById(999L)).thenReturn(null);

        mockMvc.perform(get("/media/999"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("图片不存在"));
    }

    @Test
    void fileMissingOnDisk_returns404NotServerError() throws Exception {
        TenantContext.set("default", OWNER, "USER");
        MessageMedia media = new MessageMedia();
        media.setId(11L);
        media.setUserId(OWNER);
        media.setMediaType("PNG");
        media.setObjectKey(SUB + "/" + OWNER + "/gone.png");
        when(mediaMapper.selectById(11L)).thenReturn(media);

        mockMvc.perform(get("/media/11"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void notLoggedIn_returns401() throws Exception {
        mockMvc.perform(get("/media/7"))
                .andExpect(jsonPath("$.code").value(401));
    }
}
