package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 父子索引检索器（ADR-15，P2-B）。
 * <p>
 * 用子块做相似度检索（语义聚焦、精度高），返回时替换为父块全文
 * （small-to-large：上下文完整）。子块 metadata 中的 {@code parent_text}
 * 由 {@link ParentChildDocumentTransformer} 在索引期写入。
 * </p>
 */
@Component
public class ParentChildDocumentRetriever implements DocumentRetriever {

    private static final int DEFAULT_TOP_K = 5;

    private final VectorStore vectorStore;

    public ParentChildDocumentRetriever(@Qualifier("PgVectorVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<Document> retrieve(Query query) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query.text())
                .topK(DEFAULT_TOP_K)
                .build();
        List<Document> children = vectorStore.similaritySearch(searchRequest);
        // 子块 → 父块全文（无 parent_text 的老数据回退为子块原文）
        return children.stream().map(child -> {
            Object parentText = child.getMetadata().get("parent_text");
            if (parentText instanceof String s && !s.isBlank()) {
                Document parent = new Document(s, child.getMetadata());
                parent.getMetadata().put("chunk", "parent");
                return parent;
            }
            return child;
        }).collect(Collectors.toList());
    }
}
