package cn.lwx.lwxaiagent.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 数据库 Schema 初始化器。
 * <p>
 * 统一管理 MySQL 之外的数据存储 schema：
 * <ul>
 *   <li>Elasticsearch：从 {@code es/index-mapping.json} 读取 mapping 创建索引</li>
 *   <li>Milvus：通过 SDK 创建 collection（Milvus 无 JSON DDL 标准）</li>
 * </ul>
 * <p>
 * MySQL 表结构由 {@code schema.sql} + {@code spring.sql.init} 管理。
 * 仅在 {@code rag.type=hybrid} 时生效。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
public class DatabaseSchemaInitializer {

    private final MilvusClientV2 milvusClient;
    private final ElasticsearchClient esClient;
    private final HybridRetrievalProperties props;

    public DatabaseSchemaInitializer(MilvusClientV2 milvusClient,
                                     ElasticsearchClient esClient,
                                     HybridRetrievalProperties props) {
        this.milvusClient = milvusClient;
        this.esClient = esClient;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        initMilvusCollection();
        initEsIndex();
        log.info("Database schema initialization completed");
    }

    // ========== Milvus ==========

    private void initMilvusCollection() {
        var m = props.getHybrid().getMilvus();
        boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(m.getCollectionName()).build());

        if (!exists) {
            log.info("Milvus collection '{}' doesn't exist, creating...", m.getCollectionName());
            createMilvusCollection(m);
        } else {
            loadMilvusCollection(m.getCollectionName());
        }
    }

    private void createMilvusCollection(HybridRetrievalProperties.Milvus m) {
        var idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64)
                .isPrimaryKey(true).autoID(true).build();
        var vectorField = CreateCollectionReq.FieldSchema.builder()
                .name("vector").dataType(DataType.FloatVector)
                .dimension(m.getVectorDim()).build();
        var textField = CreateCollectionReq.FieldSchema.builder()
                .name("text").dataType(DataType.VarChar)
                .maxLength(65535).build();
        var metadataField = CreateCollectionReq.FieldSchema.builder()
                .name("metadata").dataType(DataType.VarChar)
                .maxLength(65535).build();
        var tenantField = CreateCollectionReq.FieldSchema.builder()
                .name("tenant_id").dataType(DataType.VarChar)
                .maxLength(50).build();

        var schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(List.of(idField, vectorField, textField, metadataField, tenantField))
                .build();

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(m.getCollectionName())
                .collectionSchema(schema)
                .indexParams(List.of(IndexParam.builder()
                        .fieldName("vector")
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()))
                .build());
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(m.getCollectionName()).build());
        log.info("Milvus collection '{}' created and loaded (dim={})",
                m.getCollectionName(), m.getVectorDim());
    }

    private void loadMilvusCollection(String name) {
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(name).build());
        log.info("Milvus collection '{}' loaded", name);
    }

    // ========== Elasticsearch ==========

    private void initEsIndex() {
        var es = props.getHybrid().getEs();
        try {
            boolean exists = esClient.indices().exists(e -> e.index(es.getIndex())).value();
            if (exists) {
                log.info("ES index '{}' already exists", es.getIndex());
                return;
            }
        } catch (Exception e) {
            log.warn("Failed to check ES index existence: {}", e.getMessage());
        }

        log.info("Creating ES index '{}' from mapping file...", es.getIndex());
        String mappingJson = readMappingFile();
        if (mappingJson == null) return;

        try {
            esClient.indices().create(c -> c
                    .index(es.getIndex())
                    .withJson(new java.io.StringReader(mappingJson)));
            log.info("ES index '{}' created with IK analyzer", es.getIndex());
        } catch (Exception e) {
            log.warn("Failed to create ES index: {}", e.getMessage());
        }
    }

    private String readMappingFile() {
        try {
            ClassPathResource resource = new ClassPathResource("es/index-mapping.json");
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to read es/index-mapping.json", e);
            return null;
        }
    }
}
