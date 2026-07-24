package cn.lwx.lwxaiagent.rag;

import cn.hutool.core.collection.ListUtil;
import cn.lwx.lwxaiagent.config.PgvectorProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Configuration
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Resource
    private PgvectorProperties pgvectorProperties;


    @Bean
    public VectorStore PgVectorVectorStore(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        // Create a PostgreSQL data source for storing vector data
        DataSource pgDataSource = DataSourceBuilder.create()
                .url(pgvectorProperties.getUrl())
                .username(pgvectorProperties.getUsername())
                .password(pgvectorProperties.getPassword())
                .driverClassName(pgvectorProperties.getDriverClassName())
                .build();
        // Create a PostgreSQL JdbcTemplate for executing database operations
        JdbcTemplate pgJdbcTemplate = new JdbcTemplate(pgDataSource);
        // Create a PgVectorStore for storing vector data
        PgVectorStore vectorStore = PgVectorStore.builder(pgJdbcTemplate, embeddingModel)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();

        Integer count = pgJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);

        if (count != null && count == 0) {
            List<Document> documents = loveAppDocumentLoader.loadMarkdowns();

            // Deduplicate by content
            Set<String> seen = new HashSet<>();
            List<Document> uniqueDocs = new ArrayList<>();
            for (Document doc : documents) {
                if (seen.add(doc.getText())) {
                    uniqueDocs.add(doc);
                }
            }

            log.info("Original documents: {}, after dedup: {}", documents.size(), uniqueDocs.size());

            // DashScope API limit: max 10 documents per batch
            // This utility splits a list into multiple sub-lists, each no larger than the specified max size.
            // Here it is used to partition the deduplicated document list into batches of up to 10 for batch adding to the vector store.
            List<List<Document>> batches = ListUtil.partition(uniqueDocs, 10);
            for (List<Document> batch : batches) {
                vectorStore.add(batch);
            }

            log.info("Successfully loaded {} documents into vector store", uniqueDocs.size());
        } else {
            log.info("Vector store already has {} records, skipping initialization", count);
        }

        return vectorStore;

    }
}