package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <h1>MemoryService 单元测试</h1>
 *
 * <p>使用 Mockito 框架对 {@link MemoryService} 进行单元测试，
 * 所有外部依赖（{@link MessageMapper}、{@link JdbcTemplate}）都被 Mock。</p>
 *
 * <p><b>测试覆盖：</b>注册会话、查询历史、列出会话、清理历史、异常处理</p>
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    /**
     * 纯 JUnit（无 Spring 上下文）下 LambdaUpdateWrapper 需要 MyBatis-Plus 的实体表映射缓存，
     * 否则 `.eq(Message::xxx)` 的 lambda 解析抛异常（被 MemoryService 内部 try/catch 吞掉，update 从不执行）。
     */
    @BeforeEach
    void initMybatisTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                cn.lwx.lwxaiagent.entity.Message.class);
    }

    /**
     * Mock 的消息 Mapper（message 表，Phase 2 对话历史真源）。
     */
    @Mock
    private MessageMapper messageMapper;

    /**
     * Mock 的 JDBC 模板（用于验证用户-对话映射表的 SQL 语句执行）。
     */
    @Mock
    private JdbcTemplate jdbcTemplate;

    /** 加密服务（测试禁用，存明文） */
    private final EncryptionService encryptionService = new EncryptionService("", false);

    /**
     * 测试注册会话：验证 INSERT 语句是否正确执行
     */
    @Test
    void registerConversation_insertsMapping() {
        MemoryService svc = new MemoryService(messageMapper, jdbcTemplate, encryptionService);
        svc.registerConversation("user1", "conv1", "hello", "love");
        verify(jdbcTemplate).update(
            contains("INSERT IGNORE INTO user_conversations"),
            eq("user1"), eq("conv1"), anyString(), eq("love")
        );
    }

    /**
     * 测试清除历史：验证软删 message 表 + 删除用户映射表
     */
    @Test
    void clearHistory_deletesBoth() {
        MemoryService svc = new MemoryService(messageMapper, jdbcTemplate, encryptionService);
        svc.clearHistory("conv1");
        verify(messageMapper).update(isNull(), any());   // 软删 message（deleted=1）
        verify(jdbcTemplate).update(contains("DELETE FROM user_conversations"), eq("conv1"));
    }

    /**
     * 测试获取历史：验证委托给 messageMapper 按会话查询未删除消息
     */
    @Test
    void getHistory_delegates() {
        when(messageMapper.selectList(any())).thenReturn(List.of());
        MemoryService svc = new MemoryService(messageMapper, jdbcTemplate, encryptionService);
        assertEquals(0, svc.getHistory("conv1").size());
        verify(messageMapper).selectList(any());
    }

    /**
     * 测试获取历史：正确映射 role/content 为 Spring Message
     */
    @Test
    void getHistory_mapsRoles() {
        Message user = new Message();
        user.setRole("USER");
        user.setContent("hello");
        Message assistant = new Message();
        assistant.setRole("ASSISTANT");
        assistant.setContent("hi there");
        when(messageMapper.selectList(any())).thenReturn(List.of(user, assistant));

        MemoryService svc = new MemoryService(messageMapper, jdbcTemplate, encryptionService);
        List<org.springframework.ai.chat.messages.Message> history = svc.getHistory("conv1");
        assertEquals(2, history.size());
        assertEquals("hello", history.get(0).getText());
        assertEquals("hi there", history.get(1).getText());
    }

    /**
     * 测试列出用户会话：验证 SQL 查询和返回结果
     */
    @Test
    void listUserConversations_returnsList() {
        when(jdbcTemplate.queryForList(anyString(), eq("user1"), eq("love")))
            .thenReturn(List.of(Map.of("conversation_id", "c1")));
        MemoryService svc = new MemoryService(messageMapper, jdbcTemplate, encryptionService);
        List<Map<String, Object>> result = svc.listUserConversations("user1", "love");
        assertEquals(1, result.size());
        assertEquals("c1", result.get(0).get("conversation_id"));
    }

    /**
     * 测试管理员列出所有用户会话
     */
    @Test
    void listAllConversations_returnsList() {
        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(Map.of("conversation_id", "c1")));
        MemoryService svc = new MemoryService(messageMapper, jdbcTemplate, encryptionService);
        List<Map<String, Object>> result = svc.listAllConversations();
        assertEquals(1, result.size());
    }

    /**
     * 测试 JDBC 错误处理：数据库异常时不应向外抛出
     */
    @Test
    void registerConversation_jdbcError_logsOnly() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("DB error"));
        MemoryService svc = new MemoryService(messageMapper, jdbcTemplate, encryptionService);
        assertDoesNotThrow(() -> svc.registerConversation("u1", "c1", "t", "love"));
    }

    /**
     * 每个测试方法执行后清理 TenantContext（ThreadLocal），防止测试间干扰
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }
}
