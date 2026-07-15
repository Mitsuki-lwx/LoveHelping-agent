package cn.lwx.lwxaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MyKeywordEnricher {

    @Resource
    private ChatModel deepseekChatModel;

    public List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher enricher = KeywordMetadataEnricher.builder(deepseekChatModel)
                .keywordCount(5)
                .build();
        return enricher.apply(documents);
    }
}