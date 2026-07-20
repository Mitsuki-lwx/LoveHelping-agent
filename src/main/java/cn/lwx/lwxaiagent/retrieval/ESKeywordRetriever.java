package cn.lwx.lwxaiagent.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch BM25 关键词检索器。
 * 使用 ES 的 BM25 算法做关键词搜索，与 Milvus 的向量语义搜索形成互补。
 * <p>
 * BM25 擅长精确关键词匹配（如 "吵架 冷战 断联"），
 * 向量搜索擅长语义相似（如 "失恋后心情不好" 找到 "分手后如何调整心态"）。
 * 两者通过 RRF 融合排序得到更全面的结果。
 * <p>
 * 中文分词使用 IK 插件（analysis-ik），需要在 ES 中预先安装。
 * 索引定义了两个字段：title（标题）和 text（正文），都用 IK 分词。
 */
@Slf4j
public class ESKeywordRetriever {
    // ES 连接配置
    private final String uris;// 逗号分隔的 ES 节点列表，例如 "http://localhost:9200,http://localhost:9201"
    private final String indexName;// ES 索引名称，例如 "loveapp_docs"

    private RestClient restClient;// ES REST 客户端
    private ElasticsearchClient client;// ES 高级客户端

    public ESKeywordRetriever(String uris, String indexName) {
        this.uris = uris;
        this.indexName = indexName;
    }

    /**
     * 初始化：创建 ES REST 客户端并连接。
     * 使用 JacksonJsonpMapper 序列化/反序列化 JSON。
     */
    @PostConstruct
    public void init() {
        restClient = RestClient.builder(HttpHost.create(uris)).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        log.info("Connected to Elasticsearch at {}", uris);
    }

    @PreDestroy
    public void close() {
        if (restClient != null) {
            try { restClient.close(); } catch (Exception e) {
                log.warn("Failed to close ES client: {}", e.getMessage());
            }
        }
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

    /** 删除索引（用于重建时清理旧数据） */
    public void deleteIndex() {
        try {
            client.indices().delete(d -> d.index(indexName));
            log.info("ES index '{}' deleted", indexName);
        } catch (Exception e) {
            log.warn("Failed to delete ES index: {}", e.getMessage());
        }
    }

    /** 统计索引中的文档数（用于判断是否需要导入） */
    public long count() {
        try {
            var resp = client.count(c -> c.index(indexName));
            return resp.count();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 创建带 IK 中文分词的索引。
     * <p>
     * 字段说明：
     * - title：标题，用 ik_analyzer 分词
     * - text：正文，用 ik_analyzer 分词，同时保留一个 .ik 子字段用 ik_smart 粒度
     * - metadata：元数据，不建索引（index: false）只存储
     * <p>
     * 搜索时：multi_match 匹配 title 和 text（权重 text^2），
     * 同时用 match 匹配 text.ik 字段作为补充。
     */
    public void createIndexWithIk() {
        try {
            client.indices().create(c -> c
                    .index(indexName)
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                            .analysis(a -> a
                                    .analyzer("ik_analyzer", an -> an
                                            .custom(ck -> ck.tokenizer("ik_smart")))))
                    .mappings(m -> m
                            .properties("title", p -> p.text(t -> t.analyzer("ik_analyzer")))
                            .properties("text", p -> p.text(t -> t
                                    .analyzer("ik_analyzer")
                                    .fields("ik", f -> f.text(t2 -> t2.analyzer("ik_smart")))))
                            .properties("metadata", p -> p.text(t -> t.index(false)))
                            .properties("tenant_id", p -> p.keyword(k -> k))));
            log.info("ES index '{}' created with IK analyzer", indexName);
        } catch (Exception e) {
            log.warn("Failed to create ES index: {}", e.getMessage());
        }
    }

    /**
     * 将文档写入 ES 索引。
     * title 从 metadata 的 filename 中取，text 为文档原文，
     * metadata 转为 JSON 字符串存储（ES text 字段不能直接接收 Map）。
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
     * BM25 关键词搜索。
     * <p>
     * 查询策略（bool should）：
     * 1. multi_match：同时匹配 title 和 text 字段，text 权重 2 倍
     * 2. match：精确匹配 text.ik 子字段（ik_smart 分词粒度）
     * <p>
     * 返回 {@code ScoredDocument} 列表，每条包含 ES _id、Document 和 BM25 分数。
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
                        // 按租户过滤
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

                Document doc = new Document(text, Map.of(
                        "title", title != null ? title : "",
                        "es_id", hit.id() != null ? hit.id() : ""));
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
