package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.controller.MemoryController;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private ChatMemoryRepository chatMemoryRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void registerConversation_insertsMapping() {
        MemoryService svc = new MemoryService(chatMemoryRepository, jdbcTemplate);
        svc.registerConversation("user1", "conv1", "hello", "love");
        verify(jdbcTemplate).update(
            contains("INSERT IGNORE INTO user_conversations"),
            eq("user1"), eq("conv1"), anyString(), eq("love")
        );
    }

    @Test
    void clearHistory_deletesBoth() {
        MemoryService svc = new MemoryService(chatMemoryRepository, jdbcTemplate);
        svc.clearHistory("conv1");
        verify(chatMemoryRepository).deleteByConversationId("conv1");
        verify(jdbcTemplate).update(contains("DELETE FROM user_conversations"), eq("conv1"));
    }

    @Test
    void getHistory_delegates() {
        MemoryService svc = new MemoryService(chatMemoryRepository, jdbcTemplate);
        svc.getHistory("conv1");
        verify(chatMemoryRepository).findByConversationId("conv1");
    }

    @Test
    void listUserConversations_returnsList() {
        when(jdbcTemplate.queryForList(anyString(), eq("user1"), eq("love")))
            .thenReturn(List.of(Map.of("conversation_id", "c1")));
        MemoryService svc = new MemoryService(chatMemoryRepository, jdbcTemplate);
        List<Map<String, Object>> result = svc.listUserConversations("user1", "love");
        assertEquals(1, result.size());
        assertEquals("c1", result.get(0).get("conversation_id"));
    }

    @Test
    void listAllConversations_returnsList() {
        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(Map.of("conversation_id", "c1")));
        MemoryService svc = new MemoryService(chatMemoryRepository, jdbcTemplate);
        List<Map<String, Object>> result = svc.listAllConversations();
        assertEquals(1, result.size());
    }

    @Test
    void registerConversation_jdbcError_logsOnly() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("DB error"));
        MemoryService svc = new MemoryService(chatMemoryRepository, jdbcTemplate);
        assertDoesNotThrow(() -> svc.registerConversation("u1", "c1", "t", "love"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }
}
