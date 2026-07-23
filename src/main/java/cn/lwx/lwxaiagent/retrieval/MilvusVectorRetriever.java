package cn.lwx.lwxaiagent.retrieval;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.*;

/**
 * Milvus 向量数据库检索器。
 * 封装文档入库和语义搜索操作，不负责 schema 管理（由 {@link DatabaseSchemaInitializer} 处理）。
 * <p>
 * 每条数据包含：id（自动递增）、vector（浮点向量）、text（文档原文）、metadata（元数据 JSON）。
 */
@Slf4j
public class MilvusVectorRetriever {

    private final MilvusClientV2 client;
    private final String collectionName;
    private final int vectorDim;
    private final EmbeddingModel embeddingModel;

    private static final String VECTOR_FIELD = "vector";
    private static final String TEXT_FIELD = "text";
    private static final String METADATA_FIELD = "metadata";
    private static final String TENANT_FIELD = "tenant_id";

    public MilvusVectorRetriever(MilvusClientV2 client, String collectionName,
                                  int vectorDim, EmbeddingModel embeddingModel) {
        this.client = client;
        this.collectionName = collectionName;
        this.vectorDim = vectorDim;
        this.embeddingModel = embeddingModel;
        log.info("MilvusVectorRetriever ready (collection={}, dim={})", collectionName, vectorDim);
    }

    /**
     * 将文档写入 Milvus。
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
     * 语义搜索，支持按 tenantId 过滤。
     * 若 collection 缺少 tenant_id 字段则自动回退到无过滤搜索。
     */
    public List<Document> search(String query, int topK, String tenantId) {
        try {
            return doSearch(query, topK, tenantId);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("tenant_id not exist") && (tenantId != null && !tenantId.isBlank())) {
                log.info("Milvus collection lacks tenant_id field, retrying without filter");
                try {
                    return doSearch(query, topK, null);
                } catch (Exception retryEx) {
                    log.error("Milvus retry search also failed: {}", retryEx.getMessage());
                }
            }
            log.error("Milvus search failed: {}", msg, e);
            return List.of();
        }
    }

    private List<Document> doSearch(String query, int topK, String tenantId) {
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
        var firstGroup = allResults.get(0);
        log.debug("Milvus returned {} results for '{}'", firstGroup.size(), query);

        for (SearchResp.SearchResult result : firstGroup) {
            var entity = result.getEntity();
            if (entity == null) continue;

            Object textObj = entity.get(TEXT_FIELD);
            String text = textObj != null ? textObj.toString() : "";
            if (text.isBlank()) continue;

            // 解析 Milvus 中存储的 metadata JSON，合并到 Document 的 metadata 中
            Map<String, Object> docMeta = new HashMap<>();
            docMeta.put("milvus_id", String.valueOf(result.getId()));
            docMeta.put("score", String.valueOf(result.getScore()));
            Object metaObj = entity.get(METADATA_FIELD);
            if (metaObj != null) {
                try {
                    Map<String, Object> parsed = new Gson().fromJson(
                            metaObj.toString(),
                            new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                    docMeta.putAll(parsed);
                } catch (Exception ignored) {}
            }

            docs.add(new Document(text, docMeta));
        }
        return docs;
    }

    /**
     * 判断集合是否为空（用于判断是否需导入数据）。
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

    public String getCollectionName() {
        return collectionName;
    }
}
