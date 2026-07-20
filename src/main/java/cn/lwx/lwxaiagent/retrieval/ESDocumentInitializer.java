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
 * 仅在 {@code rag.type=hybrid} 时生效，首次启动时创建 IK 索引并导入文档。
 * <p>
 * 流程：
 * 1. 检查索引是否存在 → 不存在则创建（含 IK 中文分词）
 * 2. 检查索引中是否有数据 → 为空则从 .md 文件导入
 * <p>
 * 索引已存在且有数据 → 跳过（幂等，重启不重复导入）
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
            log.info("ES index '{}' doesn't exist, creating...", esRetriever.getIndexName());
            esRetriever.createIndexWithIk();
        } else {
            boolean hasData = true;
            try {
                hasData = esRetriever.count() > 0;
            } catch (Exception e) {
                log.warn("Failed to check ES document count: {}", e.getMessage());
            }
            if (hasData) {
                log.info("ES index '{}' already has data, skipping load", esRetriever.getIndexName());
                return;
            }
            // 索引存在但无数据（上次导入失败），重建索引
            log.info("ES index '{}' exists but empty, recreating...", esRetriever.getIndexName());
            esRetriever.deleteIndex();
            esRetriever.createIndexWithIk();
        }

        log.info("Loading documents into ES...");
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
        log.info("Loaded {} documents into ES index '{}'",
                count, esRetriever.getIndexName());
    }
}
