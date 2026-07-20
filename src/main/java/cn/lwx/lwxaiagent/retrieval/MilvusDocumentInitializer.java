package cn.lwx.lwxaiagent.retrieval;

import cn.lwx.lwxaiagent.rag.LoveAppDocumentLoader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Milvus 文档初始化器。
 * 仅在 {@code rag.type=hybrid} 时生效，首次启动时将 Markdown 文档导入 Milvus。
 * <p>
 * 设计逻辑：
 * - 启动时先调用 isEmpty() 检查 Milvus collection 是否为空
 * - 为空时才执行导入（避免每次重启都重复导入）
 * - 导入源与 PGvector 共享 LoveAppDocumentLoader，确保两份数据一致
 * - 导入失败的单条文档只打 warn 日志，不影响其他文档
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
public class MilvusDocumentInitializer {

    private final MilvusVectorRetriever milvusRetriever;
    private final LoveAppDocumentLoader documentLoader;

    public MilvusDocumentInitializer(MilvusVectorRetriever milvusRetriever,
                                      LoveAppDocumentLoader documentLoader) {
        this.milvusRetriever = milvusRetriever;
        this.documentLoader = documentLoader;
    }

    /**
     * 初始化入口：检查 Milvus 状态，决定是否导入文档。
     * 仅在 collection 为空时执行导入，避免重复写入。
     */
    @PostConstruct
    public void init() {
        if (!milvusRetriever.isEmpty()) {
            log.info("Milvus collection '{}' already has data, skipping load",
                    milvusRetriever.getCollectionName());
            return;
        }

        log.info("Loading documents into Milvus (tenantId=default)...");
        List<Document> docs = documentLoader.loadMarkdowns();
        int count = 0;
        for (Document doc : docs) {
            try {
                milvusRetriever.storeDocument(doc);
                count++;
            } catch (Exception e) {
                log.warn("Failed to store document to Milvus: {}", e.getMessage());
            }
        }
        log.info("Loaded {} documents into Milvus collection '{}'",
                count, milvusRetriever.getCollectionName());
    }
}
