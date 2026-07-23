package cn.lwx.lwxaiagent.retrieval;

import cn.lwx.lwxaiagent.rag.LoveAppDocumentLoader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ES 文档初始化器。
 * 仅在 {@code rag.type=hybrid} 时生效，检查索引中是否有数据，为空则导入。
 * <p>
 * 索引创建由 {@link DatabaseSchemaInitializer} 统一管理，本类只负责数据导入。
 * 幂等：重启不重复导入。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
public class ESDocumentInitializer {

    private final ESKeywordRetriever esRetriever;
    private final LoveAppDocumentLoader documentLoader;

    public ESDocumentInitializer(ESKeywordRetriever esRetriever,
                                  LoveAppDocumentLoader documentLoader) {
        this.esRetriever = esRetriever;
        this.documentLoader = documentLoader;
    }

    @PostConstruct
    public void init() {
        if (!esRetriever.indexExists()) {
            log.warn("ES index '{}' does not exist — expected to be created by DatabaseSchemaInitializer",
                    esRetriever.getIndexName());
            return;
        }

        boolean hasData;
        try {
            hasData = esRetriever.count() > 0;
        } catch (Exception e) {
            log.warn("Failed to check ES document count: {}", e.getMessage());
            return;
        }
        if (hasData) {
            log.info("ES index '{}' already has data, skipping load", esRetriever.getIndexName());
            return;
        }

        log.info("Loading documents into ES index '{}'...", esRetriever.getIndexName());
        List<Document> docs = documentLoader.loadMarkdowns();
        int count = 0;
        for (Document doc : docs) {
            try {
                esRetriever.indexDocument(doc);
                count++;
            } catch (Exception e) {
                log.warn("Failed to index document to ES: {}", e.getMessage());
            }
        }
        log.info("Loaded {} documents into ES index '{}'", count, esRetriever.getIndexName());
    }
}
