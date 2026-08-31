package cn.lwx.lwxaiagent.rag.rerank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LLM 重排 v1 单测（ADR-15 阶段 4）：保序重排 / 非法编号回退补位 / 模型失败降级 / 退化 / 截断。
 */
class LlmDocumentRerankerTest {

    private ChatModel chatModel;
    private RerankProperties properties;
    private LlmDocumentReranker reranker;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        properties = new RerankProperties();
        properties.setEnabled(true);
        properties.setMode("llm");
        reranker = new LlmDocumentReranker(chatModel, properties);
    }

    private List<Document> docs(int n) {
        List<Document> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Document("文档" + (i + 1) + " 内容 " + ("甲乙丙丁戊己庚辛壬癸").repeat(30)));
        }
        return list;
    }

    private void stubResponse(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    @Test
    void rerank_validIndices_reordersByModel() {
        stubResponse("3\n1\n5"); // 模型认为候选3最相关
        List<Document> out = reranker.rerank("查询", docs(10), 3);
        assertEquals(3, out.size());
        assertTrue(out.get(0).getText().startsWith("文档3"));
        assertTrue(out.get(1).getText().startsWith("文档1"));
        assertTrue(out.get(2).getText().startsWith("文档5"));
    }

    @Test
    void rerank_invalidAndOutOfRangeIndices_fillWithoutLoss() {
        stubResponse("99\n2\n100"); // 越界 + 合法，不足 3 个
        List<Document> out = reranker.rerank("查询", docs(5), 3);
        assertEquals(3, out.size(), "不足时用原顺序补位，不丢文档");
        assertTrue(out.get(0).getText().startsWith("文档2"));
        // 补位按原顺序：文档1、文档3 补上
        assertTrue(out.stream().anyMatch(d -> d.getText().startsWith("文档1")));
        assertTrue(out.stream().anyMatch(d -> d.getText().startsWith("文档3")));
    }

    @Test
    void rerank_modelThrows_fallbackToOriginalOrder() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("model down"));
        List<Document> out = reranker.rerank("查询", docs(10), 3);
        assertEquals(3, out.size());
        assertTrue(out.get(0).getText().startsWith("文档1"));
        assertTrue(out.get(1).getText().startsWith("文档2"));
    }

    @Test
    void rerank_candidatesTooFew_returnsAsIsWithoutModelCall() {
        List<Document> d = docs(2);
        List<Document> out = reranker.rerank("查询", d, 5);
        assertEquals(2, out.size());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void rerank_emptyCandidates_returnsEmpty() {
        assertTrue(reranker.rerank("查询", List.of(), 5).isEmpty());
        assertTrue(reranker.rerank("查询", null, 5).isEmpty());
    }

    @Test
    void rerank_promptContainsTruncatedCandidateText() {
        properties.setMaxCandidateChars(8); // 每候选只给 8 字
        stubResponse("1");
        List<Document> d = docs(3);
        reranker.rerank("查询", d, 1);
        // 传给模型的 prompt 应包含截断文本（不含全文长串）
        var promptArg = capturePrompt();
        assertFalse(promptArg.contains("甲乙丙丁戊己庚辛壬癸".repeat(30)), "应截断候选文本控 token");
        assertTrue(promptArg.contains("文档1 内容"), "候选文本截断后仍可识别");
    }

    private String capturePrompt() {
        var captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        return captor.getValue().getContents();
    }
}