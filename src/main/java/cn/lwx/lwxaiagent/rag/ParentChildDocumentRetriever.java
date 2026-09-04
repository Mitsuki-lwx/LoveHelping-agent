package cn.lwx.lwxaiagent.rag;

import cn.lwx.lwxaiagent.config.PgvectorProperties;
import cn.lwx.lwxaiagent.rag.rerank.RerankProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jdbc.DataSourceBuilder;
import javax.sql.DataSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.zaxxer.hikari.HikariDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 父子索引检索器（ADR-15，P2-B）。
 * <p>
 * 用子块做相似度检索（语义聚焦、精度高），返回时替换为父块全文
 * （small-to-large：上下文完整）。子块 metadata 中的 {@code parent_text}
 * 由 {@link ParentChildDocumentTransformer} 在索引期写入。
 * </p>
 * <p><b>混合召回（P0，RRF 接入 RAG）</b>：`app.rag.hybrid-search.enabled` 开启时，
 * 召回 = 向量 topN + pg_trgm 关键词 topN（仅限知识库子块 parent_id 存在）→ RRF 融合 → topN，
 * 兜住"四要素"这类词面可匹配、语义向量不佳的查询。关闭时保持纯向量（现状）。</p>
 * <p><b>重排（阶段 4）</b>：rerank 开启时 topK 扩为粗召回窗口 topN，精排由
 * {@link RerankDocumentPostProcessor} 在 postretrieval 阶段完成。</p>
 */
@Slf4j
@Component
public class ParentChildDocumentRetriever implements DocumentRetriever {

    // topK 由配置 app.rag.top-k 接管（2026-09-04，原硬编码 5 提高至默认 8）
    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final RerankProperties rerankProperties;
    private final JdbcTemplate pgJdbcTemplate;
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;
    private final boolean hybridEnabled;
    /** 相似度分数日志开关（排查检索质量时开；默认关，避免在线链路多一次 embedding 调用） */
    private final boolean logScore;
    /** 向量粗召回数（app.rag.top-k；默认 8：top5 去重后父文档数常不足，扩到 8 稳 Recall） */
    private final int topK;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParentChildDocumentRetriever(@Qualifier("PgVectorVectorStore") VectorStore vectorStore,
                                        RerankProperties rerankProperties,
                                        PgvectorProperties pgvectorProperties,
                                        @Qualifier("dashscopeEmbeddingModel") org.springframework.ai.embedding.EmbeddingModel embeddingModel,
                                        @Value("${app.rag.hybrid-search.enabled:false}") boolean hybridEnabled,
                                        @Value("${app.rag.log-score:false}") boolean logScore,
                                        @Value("${app.rag.top-k:8}") int topK) {
        this.vectorStore = vectorStore;
        this.rerankProperties = rerankProperties;
        this.embeddingModel = embeddingModel;
        this.logScore = logScore;
        this.topK = Math.max(3, topK);
        // 自建 pg JdbcTemplate（不注册为容器 bean，避免与 MySQL 默认 JdbcTemplate 按类型注入歧义）
        DataSource pgDataSource = DataSourceBuilder.create()
                .url(pgvectorProperties.getUrl())
                .username(pgvectorProperties.getUsername())
                .password(pgvectorProperties.getPassword())
                .driverClassName(pgvectorProperties.getDriverClassName())
                .build();
        this.pgJdbcTemplate = new JdbcTemplate(pgDataSource);
        this.hybridEnabled = hybridEnabled;
    }

    @Override
    public List<Document> retrieve(Query query) {
        int k = rerankProperties.isEnabled() && "llm".equals(rerankProperties.getMode())
                ? rerankProperties.getTopN() : topK;
        List<Document> children = hybridEnabled
                ? hybridRetrieve(query.text(), k)
                : vectorStore.similaritySearch(SearchRequest.builder().query(query.text()).topK(k).build());
        List<Document> parents = children.stream().map(this::toParent).collect(Collectors.toList());
        logRetrieved(query.text(), children);
        return parents;
    }

    /**
     * 检索可观测（2026-09-02）：记录"本次召回哪些文档"——Context Precision/Recall 复盘的前提，
     * 也是"模型答不出来到底是检索没命中还是生成不用"的判别日志。
     */
    private void logRetrieved(String query, List<Document> children) {
        try {
            StringBuilder sb = new StringBuilder("RAG_RETRIEVAL query=");
            sb.append(query.length() > 40 ? query.substring(0, 40) + "..." : query);
            sb.append(" hits=").append(children.size());
            for (int i = 0; i < children.size(); i++) {
                Document c = children.get(i);
                var meta = c.getMetadata();
                String file = String.valueOf(meta.getOrDefault("filename", "?"));
                String title = String.valueOf(meta.getOrDefault("title", ""));
                String snippet = c.getText() == null ? "" : c.getText().replace('\n', ' ');
                sb.append(" | #").append(i + 1)
                        .append(" file=").append(file.length() > 40 ? file.substring(0, 40) : file)
                        .append(" title=").append(title.length() > 20 ? title.substring(0, 20) : title)
                        .append(" [").append(snippet.length() > 45 ? snippet.substring(0, 45) : snippet).append("]");
            }
            if (logScore) {
                sb.append(" | scores=").append(scoresFor(query, children));
            }
            log.info("{}", sb);
        } catch (Exception e) {
            log.warn("RAG retrieval logging failed: {}", e.getMessage());
        }
    }

    /**
     * 命中文档的余弦相似度（1 - 距离）——Context Precision 量化的分子。
     * <p>Spring AI 1.1.8 无带分检索 API（jar 中无 SearchResult），故自行 embed 查询后
     * 用 pgvector {@code <=>} 算子对命中 id 计算；仅在 {@code app.rag.log-score=true} 时调用。</p>
     */
    private java.util.Map<String, Double> scoresFor(String query, List<Document> hits) {
        java.util.Map<String, Double> scores = new java.util.LinkedHashMap<>();
        if (hits.isEmpty() || embeddingModel == null) {
            return scores;
        }
        try {
            float[] vec = embeddingModel.embed(query);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vec.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(vec[i]);
            }
            sb.append("]");
            List<String> ids = hits.stream().map(Document::getId).filter(java.util.Objects::nonNull).toList();
            if (ids.isEmpty()) {
                return scores;
            }
            String in = String.join(",", ids.stream().map(id -> "'" + id + "'").toList());
            pgJdbcTemplate.query(
                    "SELECT id::text, 1 - (embedding <=> ?::vector) AS score FROM vector_store WHERE id::text IN (" + in + ")",
                    rs -> {
                        scores.put(rs.getString(1), rs.getDouble(2));
                    }, sb.toString());
        } catch (Exception e) {
            log.warn("RAG score logging failed: {}", e.getMessage());
        }
        return scores;
    }

    /** 混合召回：向量 + pg_trgm 关键词 → RRF 融合（仅知识库子块） */
    private List<Document> hybridRetrieve(String query, int topK) {
        List<Document> vectorDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK * 3).build());
        List<Document> keywordDocs = keywordSearch(query, topK * 3);

        Map<String, RankedDoc> fused = new LinkedHashMap<>();
        for (int i = 0; i < vectorDocs.size(); i++) {
            fused.put(vectorDocs.get(i).getId(), new RankedDoc(vectorDocs.get(i), 1.0 / (RRF_K + i + 1), 0.0));
        }
        for (int i = 0; i < keywordDocs.size(); i++) {
            Document d = keywordDocs.get(i);
            double s = 1.0 / (RRF_K + i + 1);
            RankedDoc r = fused.get(d.getId());
            if (r != null) {
                r.keywordScore += s;
            } else {
                fused.put(d.getId(), new RankedDoc(d, 0.0, s));
            }
        }
        List<Document> merged = fused.values().stream()
                .sorted((a, b) -> Double.compare(b.totalScore(), a.totalScore()))
                .limit(topK)
                .map(r -> r.doc)
                .toList();
        log.info("ParentChild hybrid recall: query='{}' vector={} keyword={} fused={}",
                query.length() > 30 ? query.substring(0, 30) + "..." : query,
                vectorDocs.size(), keywordDocs.size(), merged.size());
        return merged;
    }

/** 中文关键词召回（ADR-15 P0 落地）：改写后查询的关键词 LIKE 命中，限定知识库子块（metadata 含 parent_id） */
    private List<Document> keywordSearch(String query, int topK) {
        List<String> words = extractKeywords(query);
        log.info("LIKE kw: query='{}' words={}", query, words);
        if (words.isEmpty()) {
            return List.of();
        }
        try {
            StringBuilder sql = new StringBuilder("SELECT id, content, metadata::text FROM vector_store ")
                    .append("WHERE metadata ->> 'parent_id' IS NOT NULL AND (");
            java.util.List<Object> params = new java.util.ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("content LIKE ?");
                params.add("%" + words.get(i) + "%");
            }
            sql.append(") ORDER BY (");
            for (int i = 0; i < words.size(); i++) {
                if (i > 0) sql.append(" + ");
                sql.append("(content LIKE ?)::int");
                params.add("%" + words.get(i) + "%");
            }
            sql.append(") DESC, LENGTH(content) ASC LIMIT ?");
            params.add(topK);
            return pgJdbcTemplate.query(sql.toString(), (rs, row) -> {
                Document d = new Document(rs.getString("id"), new java.util.HashMap<>());
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = objectMapper.readValue(rs.getString("metadata"), Map.class);
                    d.getMetadata().putAll(meta);
                } catch (Exception e) {
                    log.warn("Keyword metadata parse failed: {}", e.getMessage());
                }
                return d;
            }, params.toArray());
        } catch (Exception e) {
            log.warn("ParentChild keyword search failed, fallback to vector only: {}", e.getMessage());
            return List.of();
        }
    }

    /** 从（改写后）查询提取 2-6 字关键词：空白/顿号分隔 + 去停用词 + 长度过滤 */
    private List<String> extractKeywords(String query) {
        List<String> out = new java.util.ArrayList<>();
        if (query == null) return out;
        for (String w : query.split("[\s，,、。]+")) {
            String t = w.trim();
            if (t.length() < 2 || t.length() > 12) continue;
            if (STOP_WORDS.contains(t)) continue;
            out.add(t);
            if (out.size() >= 6) break;
        }
        return out;
    }

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "的", "了", "吗", "呢", "是", "我", "你", "他", "她", "请", "帮", "给", "在", "不",
            "也", "都", "就", "想", "要", "说", "回答", "写", "列", "出", "具体", "内容", "步骤",
            "怎么", "什么", "如何", "为什么", "应该", "and", "or", "the", "a", "to", "how", "what", "for");

    /** 子块 → 父块全文（无 parent_text 的老数据回退为子块原文） */
    private Document toParent(Document child) {
        Object parentText = child.getMetadata().get("parent_text");
        if (parentText instanceof String s && !s.isBlank()) {
            Document parent = new Document(s, child.getMetadata());
            parent.getMetadata().put("chunk", "parent");
            return parent;
        }
        return child;
    }

    private static final class RankedDoc {
        final Document doc;
        double vectorScore;
        double keywordScore;

        RankedDoc(Document doc, double vectorScore, double keywordScore) {
            this.doc = doc;
            this.vectorScore = vectorScore;
            this.keywordScore = keywordScore;
        }

        double totalScore() {
            return vectorScore + keywordScore;
        }
    }
}
