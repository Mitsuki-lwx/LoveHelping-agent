package cn.lwx.lwxaiagent.retrieval;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.*;

/**
 * Milvus 向量数据库客户端。
 * 封装了与 Milvus 交互的全部操作：连接、建集合、文档入库、语义搜索。
 * <p>
 * Milvus 是专用向量数据库，需要显式定义 collection schema（与 PGvector 不同）。
 * 每条数据包含：id（自动递增）、vector（浮点向量）、text（文档原文）、metadata（元数据 JSON）。
 * <p>
 * 使用 Milvus v2 SDK 的显式 schema 模式（非动态字段），避免 JSON 解析歧义。
 */
@Slf4j
public class MilvusVectorRetriever {

    private final String host;
    private final int port;
    private final String collectionName;
    private final int vectorDim;
    private final EmbeddingModel embeddingModel;

    private MilvusClientV2 client;
    // Milvus collection schema 字段名常量
    private static final String VECTOR_FIELD = "vector";
    private static final String TEXT_FIELD = "text";
    private static final String METADATA_FIELD = "metadata";
    private static final String TENANT_FIELD = "tenant_id";

    public MilvusVectorRetriever(String host, int port, String collectionName,
                                  int vectorDim, EmbeddingModel embeddingModel) {
        this.host = host;
        this.port = port;
        this.collectionName = collectionName;
        this.vectorDim = vectorDim;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 初始化：连接 Milvus，确保集合存在。
     * 同时打印实际嵌入向量维度，便于排查配置的 vectorDim 是否匹配。
     */
    @PostConstruct
    public void init() {
        client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .build());
        log.info("Connected to Milvus at {}:{} (collection={}, dim={})",
                host, port, collectionName, vectorDim);
        ensureCollection();
        // 测试嵌入模型维度，确保与 Milvus collection schema 的 vectorDim 一致
        var testVector = embeddingModel.embed("test");
        log.info("Embedding model actual dimension: {}", testVector.length);
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
            log.info("Milvus connection closed");
        }
    }

    /**
     * 确保集合存在且可加载。
     * 如果集合不存在则创建；如果存在但无法加载（如缺少索引），则重建。
     */
    private void ensureCollection() {
        // 检查集合是否存在，通过 hasCollection() 方法判断，这是一个 Milvus v2 SDK 提供的 API。
        //主要内容是检查 Milvus 中是否已经存在指定名称的 collection，如果不存在则创建，如果存在则尝试加载，如果加载失败则重建。
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName).build());
        if (!exists) {
            log.info("Collection '{}' doesn't exist, creating...", collectionName);
            createCollection();
        } else {
            try {
                client.loadCollection(LoadCollectionReq.builder()
                        .collectionName(collectionName).build());
                log.info("Collection '{}' loaded", collectionName);
            } catch (Exception e) {
                log.warn("Failed to load collection (missing index?), recreating: {}",
                        e.getMessage());
                recreateCollection();
            }
        }
    }

    /**
     * 创建 Milvus 集合（显式 schema 模式）。相当于mysql的建表语句，就是定义了集合的结构和索引。
     * <p>
     * 定义 4 个字段：
     * - id: Int64 主键，自动递增
     * - vector: FloatVector（维度由配置决定）
     * - text: VarChar(65535)，存储文档原文
     * - metadata: VarChar(65535)，存储元数据的 JSON 字符串
     * <p>
     * 同时为 vector 字段创建 COSINE 相似度索引。
     */
    private void createCollection() {
        //CreateCollectionReq.FieldSchema.builder() 是 Milvus v2 SDK 提供的一个构建器，用于定义集合的字段 schema。下面是对每个字段的定义：
        var idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64)
                .isPrimaryKey(true).autoID(true).build();
        var vectorField = CreateCollectionReq.FieldSchema.builder()
                .name(VECTOR_FIELD).dataType(DataType.FloatVector)
                .dimension(vectorDim).build();
        var textField = CreateCollectionReq.FieldSchema.builder()
                .name(TEXT_FIELD).dataType(DataType.VarChar)
                .maxLength(65535).build();
        var metadataField = CreateCollectionReq.FieldSchema.builder()
                .name(METADATA_FIELD).dataType(DataType.VarChar)
                .maxLength(65535).build();
        var tenantField = CreateCollectionReq.FieldSchema.builder()
                .name(TENANT_FIELD).dataType(DataType.VarChar)
                .maxLength(50).build();

        var schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(List.of(idField, vectorField, textField, metadataField, tenantField))
                .build();
        //这是在创建 Milvus collection 时定义的索引参数，指定了对 vector 字段使用 COSINE 相似度进行索引。这样可以在搜索时使用余弦相似度来计算向量之间的相似性。
        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(List.of(IndexParam.builder()//这是在创建 Milvus collection 时定义的索引参数，指定了对 vector 字段使用 COSINE 相似度进行索引。这样可以在搜索时使用余弦相似度来计算向量之间的相似性。
                        .fieldName(VECTOR_FIELD)
                        .metricType(IndexParam.MetricType.COSINE)
                        .build()))
                .build());
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName).build());
        log.info("Collection '{}' created and loaded", collectionName);
    }

    /**
     * 将文档写入 Milvus。
     * 写入格式为 JsonObject，因为 Milvus v2 SDK 的 InsertReq.data() 接受 List<JsonObject>。
     * <p>
     * 流程：
     * 1. 调用 embeddingModel.embed(text) 将文本转为 float[] 向量
     * 2. 将 float[] 转为 List<Float>（JSON 数组格式）
     * 3. 构造 {"vector": [...], "text": "...", "metadata": "..."} 插入
     */
    public void storeDocument(Document doc) {
        var text = doc.getText();
        var metadata = new Gson().toJson(doc.getMetadata());
        var vector = embeddingModel.embed(text);
        var tenantId = (String) doc.getMetadata().getOrDefault("tenantId", "default");

        var row = new JsonObject();
        var gson = new Gson();
        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float v : vector) vectorList.add(v);
        row.add(VECTOR_FIELD, gson.toJsonTree(vectorList));
        row.addProperty(TEXT_FIELD, text);
        row.addProperty(METADATA_FIELD, metadata);
        row.addProperty(TENANT_FIELD, tenantId);

        client.insert(InsertReq.builder()
                .collectionName(collectionName)
                .data(List.of(row))
                .build());
    }

    /**
     * 语义搜索：用查询文本生成向量，在 Milvus 中找最相似的 topK 条文档。
     * <p>
     * 注意事项：
     * - ConsistencyLevel.EVENTUALLY 保证低延迟，适合实时搜索
     * - outputFields 设为 "*" 返回所有字段
     * - 每条结果附上 milvus_id 和 score，供 RRF 融合排序使用
     */
    /**
     * 语义搜索：支持按 tenantId 过滤。
     *
     * @param query    用户查询文本
     * @param topK     返回结果数
     * @param tenantId 租户 ID（null 或空时不过滤）
     */
    public List<Document> search(String query, int topK, String tenantId) {
        try {
            var queryVector = embeddingModel.embed(query);
            log.debug("Milvus search: query='{}' topK={} tenant={}", query, topK, tenantId);

            var builder = SearchReq.builder()
                    .collectionName(collectionName)
                    .data(List.of(new FloatVec(queryVector)))
                    .topK(topK)
                    .outputFields(List.of("*"))
                    .consistencyLevel(ConsistencyLevel.EVENTUALLY);

            if (tenantId != null && !tenantId.isBlank()) {
                builder.filter(TENANT_FIELD + " == \"" + tenantId + "\"");
            }

            var resp = client.search(builder.build());

            var allResults = resp.getSearchResults();
            if (allResults == null || allResults.isEmpty() || allResults.get(0).isEmpty()) {
                log.warn("Milvus search returned no results for query='{}'", query);
                return List.of();
            }

            List<Document> docs = new ArrayList<>();
            var firstGroup = allResults.get(0);// Milvus 返回的结果是 List<List<SearchResult>>，这里取第一个 List<SearchResult>，因为我们只传入了一个查询向量。
            log.debug("Milvus returned {} results for '{}'", firstGroup.size(), query);

            for (SearchResp.SearchResult result : firstGroup) {
                var entity = result.getEntity();// 每个 SearchResult 包含一个 Entity 对象，Entity 中包含了我们在 collection schema 中定义的字段值。
                if (entity == null) continue;

                Object textObj = entity.get(TEXT_FIELD);// 从 Entity 中获取 text 字段的值，这里是文档原文。
                String text = textObj != null ? textObj.toString() : "";
                if (text.isBlank()) continue;

                docs.add(new Document(text, Map.of(
                        "milvus_id", String.valueOf(result.getId()),// 从 SearchResult 中获取 milvus_id，这个 id 是 Milvus 自动生成的唯一标识，对应 collection 中的每条数据。
                        "score", String.valueOf(result.getScore()))));
            }
            return docs;
        } catch (Exception e) {
            log.error("Milvus search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 判断集合是否为空（用于 MilvusDocumentInitializer 判断是否需导入数据）。
     * 用全零向量做一次 topK=1 的搜索，有返回结果则说明非空。
     */
    public boolean isEmpty() {
        try {
            var resp = client.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .data(List.of(new FloatVec(new float[vectorDim])))
                    .topK(1)
                    .consistencyLevel(ConsistencyLevel.EVENTUALLY)
                    .build());
            var allResults = resp.getSearchResults();
            return allResults == null || allResults.isEmpty()
                    || allResults.get(0) == null || allResults.get(0).isEmpty();
        } catch (Exception e) {
            log.warn("Failed to check if collection is empty: {}", e.getMessage());
            return true;
        }
    }

    /** 删除并重建集合（用于 collection 状态异常时恢复） */
    public void recreateCollection() {
        try {
            client.dropCollection(DropCollectionReq.builder()
                    .collectionName(collectionName).build());
        } catch (Exception e) {
            log.warn("Failed to drop collection: {}", e.getMessage());
        }
        createCollection();
    }

    public String getCollectionName() {
        return collectionName;
    }

    /** 删除整个 Milvus collection */
    public void deleteCollection() {
        client.dropCollection(DropCollectionReq.builder()
                .collectionName(collectionName).build());
        log.info("Collection '{}' deleted", collectionName);
    }
}
