package cn.lwx.lwxaiagent.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch BM25 关键词检索器。
 * 使用 ES 的 BM25 算法做关键词搜索，与 Milvus 的向量语义搜索形成互补。
 * 不负责索引创建（由 {@link DatabaseSchemaInitializer} 处理）。
 * <p>
 * 中文分词使用 IK 插件（analysis-ik），需在 ES 中预先安装。
 */
@Slf4j
public class ESKeywordRetriever {

    private final ElasticsearchClient client;
    private final String indexName;

    public ESKeywordRetriever(ElasticsearchClient client, String indexName) {
        this.client = client;
        this.indexName = indexName;
        log.info("ESKeywordRetriever ready (index={})", indexName);
    }

    public String getIndexName() { return indexName; }

    /** 检查索引是否存在 */
    public boolean indexExists() {
        try {
            return client.indices().exists(e -> e.index(indexName)).value();
        } catch (Exception e) {
            return false;
        }
    }

    /** 统计索引中的文档数 */
    public long count() {
        try {
            var resp = client.count(c -> c.index(indexName));
            return resp.count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 删除索引 */
    public void deleteIndex() {
        try {
            client.indices().delete(d -> d.index(indexName));
            log.info("ES index '{}' deleted", indexName);
        } catch (Exception e) {
            log.warn("Failed to delete ES index: {}", e.getMessage());
        }
    }

    /**
     * 将文档写入 ES 索引。
     */
    public void indexDocument(Document doc) {
        try {
            String metadataJson = new com.google.gson.Gson().toJson(doc.getMetadata());
            String tenantId = (String) doc.getMetadata().getOrDefault("tenantId", "default");
            client.index(i -> i
                    .index(indexName)
                    .id(doc.getId())
                    .document(Map.of(
                            "title", doc.getMetadata().getOrDefault("filename", ""),
                            "text", doc.getText(),
                            "metadata", metadataJson,
                            "tenant_id", tenantId)));
        } catch (Exception e) {
            log.warn("Failed to index document to ES: {}", e.getMessage());
        }
    }

    /**
     * BM25 关键词搜索，支持按 tenantId 过滤。
     */
    public List<ScoredDocument> search(String query, int topN, String tenantId) {
        try {
            SearchResponse<Map> resp = client.search(s -> s
                    .index(indexName)
                    .query(q -> q.bool(b -> {
                        b.should(s1 -> s1.multiMatch(mm -> mm
                                .fields("text^2", "title")
                                .query(query)));
                        b.should(s2 -> s2.match(m -> m
                                .field("text.ik")
                                .query(query)));
                        if (tenantId != null && !tenantId.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)));
                        }
                        return b;
                    }))
                    .size(topN), Map.class);

            List<ScoredDocument> results = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                Map source = hit.source();
                String text = source != null ? (String) source.getOrDefault("text", "") : "";
                String title = source != null ? (String) source.getOrDefault("title", "") : "";
                double score = hit.score() != null ? hit.score() : 0.0;

                Map<String, Object> docMeta = new java.util.HashMap<>();
                docMeta.put("title", title != null ? title : "");
                docMeta.put("es_id", hit.id() != null ? hit.id() : "");
                // 解析 ES 中存储的 metadata JSON，合并到 Document metadata
                String metaJson = source != null ? (String) source.getOrDefault("metadata", "") : "";
                if (metaJson != null && !metaJson.isBlank()) {
                    try {
                        Map<String, Object> parsed = new com.google.gson.Gson().fromJson(
                                metaJson,
                                new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                        docMeta.putAll(parsed);
                    } catch (Exception ignored) {}
                }

                Document doc = new Document(text, docMeta);
                results.add(new ScoredDocument(hit.id(), doc, score));
            }
            log.debug("ES search returned {} results for '{}'", results.size(), query);
            return results;
        } catch (Exception e) {
            log.warn("ES search failed: {}", e.getMessage());
            return List.of();
        }
    }
}
