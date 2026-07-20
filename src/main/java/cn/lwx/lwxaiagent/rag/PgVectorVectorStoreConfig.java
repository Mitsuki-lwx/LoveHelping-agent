package cn.lwx.lwxaiagent.rag;

import cn.hutool.core.collection.ListUtil;
import cn.lwx.lwxaiagent.config.PgvectorProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
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
    public VectorStore PgVectorVectorStore(EmbeddingModel embeddingModel) {
        // 创建一个 PostgreSQL 数据源 用来存储向量数据
        DataSource pgDataSource = DataSourceBuilder.create()
                .url(pgvectorProperties.getUrl())
                .username(pgvectorProperties.getUsername())
                .password(pgvectorProperties.getPassword())
                .driverClassName(pgvectorProperties.getDriverClassName())
                .build();
        // 创建一个 PostgreSQL JdbcTemplate，用来执行数据库操作
        JdbcTemplate pgJdbcTemplate = new JdbcTemplate(pgDataSource);
        // 创建一个 PgVectorStore，用来存储向量数据
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

            // 根据内容去重
            Set<String> seen = new HashSet<>();
            List<Document> uniqueDocs = new ArrayList<>();
            for (Document doc : documents) {
                if (seen.add(doc.getText())) {
                    uniqueDocs.add(doc);
                }
            }

            log.info("原始文档数: {}, 去重后: {}", documents.size(), uniqueDocs.size());

            // DashScope API限制：每批最多10条
            //这是一个工具类，用于将一个列表分割成多个子列表，每个子列表的大小不超过指定的最大值。在这里，它被用来将去重后的文档列表分割成每批最多10条的子列表，以便批量添加到向量存储中。
            List<List<Document>> batches = ListUtil.partition(uniqueDocs, 10);
            for (List<Document> batch : batches) {
                vectorStore.add(batch);
            }

            log.info("成功加载 {} 个文档到向量库", uniqueDocs.size());
        } else {
            log.info("向量库已有 {} 条记录，跳过初始化", count);
        }

        return vectorStore;

    }
}