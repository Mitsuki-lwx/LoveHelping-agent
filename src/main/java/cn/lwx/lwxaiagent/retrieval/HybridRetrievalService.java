package cn.lwx.lwxaiagent.retrieval;

import cn.lwx.lwxaiagent.config.PgvectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 统一检索门面服务：混合检索（pgvector 语义 + pg_trgm 关键词）→ RRF 融合。
 * <p>
 * 原 Milvus+ES+RRF 已移除（ADR-1），此为重新实现的混合检索（Phase 3）。
 * 纯语义检索为默认路径；开启混合检索后，同时执行向量搜索和关键词搜索，
 * 用 RRF（Reciprocal Rank Fusion）合并排序。
 * </p>
 */
@Slf4j
@Component
public class HybridRetrievalService {

    private final VectorStore vectorStore;
    private final JdbcTemplate pgJdbcTemplate;
    private final boolean hybridEnabled;

    /** RRF 常数 k（防止除以零） */
    private static final int RRF_K = 60;

    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public HybridRetrievalService(@Qualifier("PgVectorVectorStore") VectorStore vectorStore,
                                   PgvectorProperties pgvectorProperties,
                                   @Value("${app.rag.hybrid-search.enabled:true}") boolean hybridEnabled,
                                   io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
        DataSource ds = DataSourceBuilder.create()
                .url(pgvectorProperties.getUrl())
                .username(pgvectorProperties.getUsername())
                .password(pgvectorProperties.getPassword())
                .driverClassName(pgvectorProperties.getDriverClassName())
                .build();
        this.pgJdbcTemplate = new JdbcTemplate(ds);
        this.hybridEnabled = hybridEnabled;
    }

    /**
     * 检索入口：向量检索（或混合检索，开启时）。
     */
    public List<Document> search(String query, int userTopK, String tenantId) {
        // 可观测（2026-09-05）：rag.hit/rag.empty——空结果率监控数据源（08 §2.2 契约兑现）
        List<Document> result = doSearch(query, userTopK, tenantId);
        meterRegistry.counter("rag.hit", "store", "pgvector").increment();
        if (result.isEmpty()) {
            meterRegistry.counter("rag.empty", "store", "pgvector").increment();
        }
        return result;
    }

    /** 原检索实现（被 search 包装打点，2026-09-05） */
    private List<Document> doSearch(String query, int userTopK, String tenantId) {
        if (!hybridEnabled) {
            // 纯向量检索
            return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(userTopK).build());
        }

        // 混合检索：向量 + 关键词 → RRF 融合
        List<Document> vectorDocs = vectorSearch(query, userTopK * 3);
        List<Document> keywordDocs = keywordSearch(query, userTopK * 3);

        // RRF 融合
        Map<String, RankedDoc> fused = new LinkedHashMap<>();

        for (int i = 0; i < vectorDocs.size(); i++) {
            String id = vectorDocs.get(i).getId();
            fused.put(id, new RankedDoc(vectorDocs.get(i), 1.0 / (RRF_K + i + 1), 0.0));
        }

        for (int i = 0; i < keywordDocs.size(); i++) {
            String id = keywordDocs.get(i).getId();
            double keywordScore = 1.0 / (RRF_K + i + 1);
            if (fused.containsKey(id)) {
                fused.get(id).rrfScore += keywordScore;
            } else {
                fused.put(id, new RankedDoc(keywordDocs.get(i), 0.0, keywordScore));
            }
        }

        // 按 RRF 总分降序排列，取 topK
        List<Document> results = fused.values().stream()
                .sorted((a, b) -> Double.compare(b.totalScore(), a.totalScore()))
                .limit(userTopK)
                .map(r -> r.doc)
                .toList();

        log.info("Hybrid search: query='{}', vector={}, keyword={}, fused={}",
                query.length() > 30 ? query.substring(0, 30) + "..." : query,
                vectorDocs.size(), keywordDocs.size(), results.size());
        return results;
    }

    /** 向量检索（pgvector 余弦相似度） */
    private List<Document> vectorSearch(String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).build());
    }

    /** 关键词检索（pg_trgm 模糊匹配） */
    private List<Document> keywordSearch(String query, int topK) {
        try {
            // 用 pg_trgm 的 similarity 函数做关键词匹配
            String sql = "SELECT id, content, metadata::text, "
                    + "similarity(content, ?) AS score "
                    + "FROM vector_store "
                    + "WHERE content % ? "
                    + "ORDER BY score DESC LIMIT ?";
            return pgJdbcTemplate.query(sql, (rs, row) -> {
                String id = rs.getString("id");
                String content = rs.getString("content");
                java.util.Map<String, Object> meta = new java.util.HashMap<>();
                try {
                    String metaStr = rs.getString("metadata");
                    if (metaStr != null) {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        meta = mapper.readValue(metaStr, java.util.Map.class);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse metadata: {}", e.getMessage());
                }
                return new Document(id, content, meta);
            }, query, query, topK);
        } catch (Exception e) {
            log.warn("Keyword search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** RRF 临时排序记录 */
    private static class RankedDoc {
        final Document doc;
        double rrfScore;

        RankedDoc(Document doc, double vectorScore, double keywordScore) {
            this.doc = doc;
            this.rrfScore = vectorScore + keywordScore;
        }

        double totalScore() { return rrfScore; }
    }
}