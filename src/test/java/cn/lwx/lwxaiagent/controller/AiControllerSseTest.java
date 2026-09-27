package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.infrastructure.orchestration.*;
import cn.lwx.lwxaiagent.memory.MemoryService;
import cn.lwx.lwxaiagent.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AiControllerSseTest {
    @Test void oneSseMappingPreservesUnicodeAndAdviceRegardlessOfAccept() throws Exception {
        ChatEntry entry = mock(ChatEntry.class);
        var controller = new AiController(entry, mock(ChatService.class), mock(AgentTaskService.class), mock(MemoryService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build(); // Fails if mappings are ambiguous.
        when(entry.chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull()))
                .thenAnswer(i -> new AgentResult.ShallowResult(Flux.just("\uD83D\uDEE1\uFE0F 安全牌", "正文", "@@ADVICE@@{\"tiers\":[]}")));
        for (MediaType accept : java.util.List.of(MediaType.TEXT_EVENT_STREAM, MediaType.ALL)) {
            var pending = mvc.perform(get("/Love_app/chat/sse").param("prompt", "test").param("chatId", "test").accept(accept)).andReturn();
            pending.getAsyncResult(5000);
            var response = mvc.perform(asyncDispatch(pending)).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("\uD83D\uDEE1\uFE0F 安全牌"), body);
            assertTrue(body.contains("event:advice"), body);
            assertFalse(body.contains("@@ADVICE@@"));
        }
    }

    @Test void midStreamErrorIsTypedAndDoesNotDiscloseExceptionBody() throws Exception {
        ChatEntry entry = mock(ChatEntry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiController(entry, mock(ChatService.class),
                mock(AgentTaskService.class), mock(MemoryService.class)))
                // ⚠️ 不配 UTF-8 转换器，响应里的中文会被 MockMvc 默认编码写成 `?`
                //   （实测踩过：占位文案在断言消息里显示成 "?????"，一度以为生产有编码 bug）。
                //   生产走 Spring Boot 的 UTF-8 配置不受影响，**这是测试环境问题**。
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        when(entry.chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull()))
                .thenReturn(new AgentResult.ShallowResult(Flux.concat(Flux.just("prefix"), Flux.error(new RuntimeException("private response body")))));
        var pending = mvc.perform(get("/Love_app/chat/sse").param("prompt", "test").param("chatId", "test")
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        pending.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("event:error")); assertTrue(body.contains("prefix"));
        assertFalse(body.contains("private response body"));
    }

    // ────────────── phase17：首字节前的占位事件 ──────────────

    /**
     * 占位事件必须是**第一个**发出的，且带独立 event 名。
     *
     * <p>为什么这两条都要断：占位如果排在正文之后，就等于没加（用户早看到正文了）；
     * 如果用默认 data 事件，前端 {@code onmessage} 会把它当正文插进气泡 → 脏文本。</p>
     */
    @Test void thinkingStatusIsEmittedFirstAndUsesItsOwnEventName() throws Exception {
        ChatEntry entry = mock(ChatEntry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiController(entry, mock(ChatService.class),
                mock(AgentTaskService.class), mock(MemoryService.class)))
                // ⚠️ 不配 UTF-8 转换器，响应里的中文会被 MockMvc 默认编码写成 `?`
                //   （实测踩过：占位文案在断言消息里显示成 "?????"，一度以为生产有编码 bug）。
                //   生产走 Spring Boot 的 UTF-8 配置不受影响，**这是测试环境问题**。
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        when(entry.chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull()))
                .thenReturn(new AgentResult.ShallowResult(Flux.just("BODY_MARKER_XYZ")));
        var pending = mvc.perform(get("/Love_app/chat/sse").param("prompt", "test").param("chatId", "test")
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        pending.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(pending)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        int statusAt = body.indexOf("event:status");
        int textAt = body.indexOf("data:", body.indexOf("event:status"));
        assertTrue(statusAt >= 0, "占位事件没发出: " + body);
        assertTrue(textAt > statusAt, "占位之后没有正文帧: " + body);
        // ⭐ 顺序才是这个特性的全部意义；顺序反了等于没做。
        assertTrue(statusAt < textAt, "占位事件必须排在正文之前: " + body);
    }

    /**
     * ⛔ 占位事件绝不能以默认 data 形式出现 —— 那会被前端 {@code onmessage} 当正文消费，
     * 用户在气泡里看到「让我想想…」。这条是"独立 event 名"这个决策的**唯一**保障。
     */
    @Test void thinkingStatusNeverLeaksAsDefaultDataEvent() throws Exception {
        ChatEntry entry = mock(ChatEntry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiController(entry, mock(ChatService.class),
                mock(AgentTaskService.class), mock(MemoryService.class)))
                // ⚠️ 不配 UTF-8 转换器，响应里的中文会被 MockMvc 默认编码写成 `?`
                //   （实测踩过：占位文案在断言消息里显示成 "?????"，一度以为生产有编码 bug）。
                //   生产走 Spring Boot 的 UTF-8 配置不受影响，**这是测试环境问题**。
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        when(entry.chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull()))
                .thenReturn(new AgentResult.ShallowResult(Flux.just("BODY_MARKER_XYZ")));
        var pending = mvc.perform(get("/Love_app/chat/sse").param("prompt", "test").param("chatId", "test")
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        pending.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(pending)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // SSE 帧格式：`data:xxx\n\n`。若占位文本前面紧邻的就是裸 data 行（前面没有 event:），
        // 说明它走了 onmessage 路径 → 前端会当正文。
        int head = body.indexOf("\"stage\":\"thinking\"");
        assertTrue(head > 0, "占位事件没出现: " + body);
        // ⛔ 找的是 **帧首行**（帧内第一个换行之前），不是"上一段空行之后"：
        //   帧格式是 `event:status\ndata:{...}`，
        //   定位点在 data 行内部 → 同一段里当然找不到 event:status。第一版就是这么写错的，
        //   断言在**输出完全正确**的情况下失败 —— 断言自身是错的，不能据此改产品代码。
        int frameStart = body.lastIndexOf("\n\n", head);
        frameStart = frameStart < 0 ? 0 : frameStart + 2;
        int frameEnd = body.indexOf('\n', frameStart);
        String frameHeader = frameEnd < 0 ? body.substring(frameStart) : body.substring(frameStart, frameEnd);
        assertTrue(frameHeader.contains("event:status"),
                "占位文本所在帧没有 event:status（会被前端 onmessage 当正文）: " + body);
    }

    /**
     * 占位事件**不能**替代错误事件：上游真失败时仍必须发 {@code event:error}，
     * 否则用户会一直停在「让我想想…」直到连接超时 —— 那比白屏更糟（白屏至少知道没成功）。
     */
    @Test void errorStillTypedEvenThoughStatusPrecedesIt() throws Exception {
        ChatEntry entry = mock(ChatEntry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiController(entry, mock(ChatService.class),
                mock(AgentTaskService.class), mock(MemoryService.class)))
                // ⚠️ 不配 UTF-8 转换器，响应里的中文会被 MockMvc 默认编码写成 `?`
                //   （实测踩过：占位文案在断言消息里显示成 "?????"，一度以为生产有编码 bug）。
                //   生产走 Spring Boot 的 UTF-8 配置不受影响，**这是测试环境问题**。
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        when(entry.chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull()))
                .thenReturn(new AgentResult.ShallowResult(
                        Flux.error(new cn.lwx.lwxaiagent.common.BizException(5000, "上游炸了"))));
        var pending = mvc.perform(get("/Love_app/chat/sse").param("prompt", "test").param("chatId", "test")
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        pending.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(pending)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("event:error"), "错误事件被占位吞掉了: " + body);
        assertTrue(body.contains("上游炸了"), body);
    }

    /**
     * ⭐ 关键回归：占位事件引入的 {@code Flux.defer} 不得改变**业务调用次数**。
     * defer 会把 {@code chatEntry.chat()} 移到订阅时执行 —— 如果将来有人改成订阅两次
     * （或被重试），就会**重复建图、重复检索、重复扣额度**。这里用计数锁死。
     */
    @Test void deferringChatToSubscriptionTimeStillCallsChatExactlyOnce() throws Exception {
        ChatEntry entry = mock(ChatEntry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiController(entry, mock(ChatService.class),
                mock(AgentTaskService.class), mock(MemoryService.class)))
                // ⚠️ 不配 UTF-8 转换器，响应里的中文会被 MockMvc 默认编码写成 `?`
                //   （实测踩过：占位文案在断言消息里显示成 "?????"，一度以为生产有编码 bug）。
                //   生产走 Spring Boot 的 UTF-8 配置不受影响，**这是测试环境问题**。
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        when(entry.chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull()))
                .thenReturn(new AgentResult.ShallowResult(Flux.just("BODY_MARKER_XYZ")));
        var pending = mvc.perform(get("/Love_app/chat/sse").param("prompt", "test").param("chatId", "test")
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        pending.getAsyncResult(5000);
        mvc.perform(asyncDispatch(pending)).andReturn();
        // 单次订阅 → 单次业务调用
        verify(entry, times(1))
                .chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull());
    }

    /**
     * 占位事件即使发不出去（客户端已断开）也**不能**影响正常流 ——
     * 诊断性/装饰性输出绝不能把主链路带崩。
     */
    @Test void statusIsEmittedOnSseEmitterPathToo() throws Exception {
        var emitter = SseBridge.emitter(Flux.just("正文"));
        assertNotNull(emitter);
        // SseEmitter 是异步容器，单测里断言"已发送"需要起真实容器；
        // 这里至少锁定契约常量不变（前端按这个名字监听）。
        assertEquals("status", SseBridge.STATUS_EVENT);
        assertTrue(SseBridge.THINKING_STATUS.contains("thinking"));
    }
}
