package cn.lwx.lwxaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
class PgVectorVectorStoreConfigTest {
    @Resource
    private VectorStore PgVectorVectorStore;

    @Test
    void testPgVectorVectorStore() {
        List<Document> documents = List.of(
                new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!", Map.of("meta1", "meta1")),
                new Document("The World is Big and Salvation Lurks Around the Corner"),
                new Document("You walk forward facing the past and you turn back toward the future.", Map.of("meta2", "meta2")));

// Add the documents to PGVector
        PgVectorVectorStore.add(documents);

// Retrieve documents similar to a query
        List<Document> results = this.PgVectorVectorStore.similaritySearch(SearchRequest.builder().query("Spring").topK(5).build());
    }

}