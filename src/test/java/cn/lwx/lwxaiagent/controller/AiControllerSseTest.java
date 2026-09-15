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
                mock(AgentTaskService.class), mock(MemoryService.class))).build();
        when(entry.chat(anyString(), anyString(), anyList(), eq(false), eq(false), isNull()))
                .thenReturn(new AgentResult.ShallowResult(Flux.concat(Flux.just("prefix"), Flux.error(new RuntimeException("private response body")))));
        var pending = mvc.perform(get("/Love_app/chat/sse").param("prompt", "test").param("chatId", "test")
                .accept(MediaType.TEXT_EVENT_STREAM)).andReturn();
        pending.getAsyncResult(5000);
        String body = mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("event:error")); assertTrue(body.contains("prefix"));
        assertFalse(body.contains("private response body"));
    }
}
