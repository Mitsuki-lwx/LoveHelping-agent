package cn.lwx.lwxaiagent.phase0;

import cn.lwx.lwxaiagent.agent.LoveManus;
import cn.lwx.lwxaiagent.agent.model.AgentState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@Slf4j
public class Phase0EvaluationTest {

    @Resource
    private ToolCallback[] toolCallbacks;

    @Resource
    private ChatModel deepseekChatModel;

    @Resource
    private JdbcChatMemoryRepository chatMemoryRepository;

    private final AtomicInteger convId = new AtomicInteger(0);

    @Test
    void runLoveManusEvaluation() throws Exception {
        log.info("===== Phase 0: Evaluating LoveManus agent (12 conversations) =====");

        for (TestQuery tq : queries()) {
            evaluate(tq.message, tq.category);
        }

        ConversationTracker.saveReport();
        log.info("===== Phase 0 complete: {} conversations =====", convId.get());
    }

    record TestQuery(String message, String category) {}

    static List<TestQuery> queries() {
        return List.of(
                new TestQuery("你好，最近心情不太好", "chat"),
                new TestQuery("和男朋友吵架了，他三天没联系我，怎么办", "chat"),
                new TestQuery("暗恋公司同事一年了，该不该表白", "chat"),
                new TestQuery("搜索知识库：失恋后如何调整心态", "rag"),
                new TestQuery("搜索知识库：追求心仪对象的方法", "rag"),
                new TestQuery("搜索知识库：夫妻沟通技巧", "rag"),
                new TestQuery("搜索一下最近有哪些好看的爱情电影", "tools"),
                new TestQuery("帮我搜索'情侣必做的100件事'", "tools"),
                new TestQuery("搜索一下上海适合约会的地方", "tools"),
                new TestQuery("我失恋了很痛苦，先帮我搜索一些疗伤的方法，再给我一些建议", "complex"),
                new TestQuery("帮我搜索约会餐厅推荐，然后整理成清单", "complex"),
                new TestQuery("搜索适合情侣的室内活动，然后推荐给我", "complex")
        );
    }

    private void evaluate(String message, String category) {
        String sessionId = UUID.randomUUID().toString();
        ConversationTracker.TrackerSession session = ConversationTracker.begin(message, category);

        LoveManus agent = new LoveManus(toolCallbacks, deepseekChatModel);
        agent.setName("LoveManus-Eval");
        ChatMemory mysqlMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(50)
                .build();
        agent.setChatMemory(mysqlMemory);
        agent.setConversationId(sessionId);

        long t0 = System.currentTimeMillis();
        boolean toolsCalled = false;
        boolean retrievalCalled = message.contains("搜索知识库");
        int steps = 0;

        try {
            agent.getMessageList().add(new UserMessage(message));
            agent.setState(AgentState.RUNNING);

            for (int i = 0; i < agent.getMaxSteps(); i++) {
                boolean shouldAct = agent.think();
                steps = i + 1;
                if (!shouldAct) break;

                String result = agent.act();
                toolsCalled = true;
                if (result != null && result.contains("searchKnowledge")) {
                    retrievalCalled = true;
                }
                if (agent.getState() == AgentState.FINISHED) break;
            }
            agent.setState(AgentState.FINISHED);

            long elapsed = System.currentTimeMillis() - t0;
            int conv = convId.incrementAndGet();
            session.complete("", steps, toolsCalled, retrievalCalled);
            log.info("[{}/12] {} | {}ms | steps={} | tools={} | {}", conv, category, elapsed, steps, toolsCalled, truncate(message, 50));
        } catch (Exception e) {
            session.fail(e.getMessage());
            log.error("FAILED: {} | {}", message, e.getMessage() != null ? e.getMessage() : "null");
        } finally {
            try { agent.cleanup(); } catch (Exception ignored) {}
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
