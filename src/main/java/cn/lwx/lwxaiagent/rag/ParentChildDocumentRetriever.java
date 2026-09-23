package cn.lwx.lwxaiagent.rag;

import cn.lwx.lwxaiagent.config.PgvectorProperties;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import cn.lwx.lwxaiagent.rag.rerank.RerankProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.TraceContext;
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
    /** ADR-26：检索链路埋点（只观察，不改变检索行为） */
    private final AiTelemetry telemetry;

    public ParentChildDocumentRetriever(@Qualifier("PgVectorVectorStore") VectorStore vectorStore,
                                        RerankProperties rerankProperties,
                                        PgvectorProperties pgvectorProperties,
                                        org.springframework.ai.embedding.EmbeddingModel embeddingModel,
                                        @Value("${app.rag.hybrid-search.enabled:false}") boolean hybridEnabled,
                                        @Value("${app.rag.log-score:false}") boolean logScore,
                                        @Value("${app.rag.top-k:8}") int topK,
                                        AiTelemetry telemetry) {
        this.vectorStore = vectorStore;
        this.rerankProperties = rerankProperties;
        this.embeddingModel = embeddingModel;
        this.logScore = logScore;
        this.topK = Math.max(3, topK);
        this.telemetry = telemetry;
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
        // ADR-26：观察者 span。只做 tag/end，不参与任何取值与分支，异常原样抛出。
        Object propagated = query.context() == null ? null : query.context().get(AiTelemetry.PARENT_CONTEXT_KEY);
        TraceContext captured = telemetry.capture();
        var span = telemetry.start("rag.retrieve",
                captured != null ? captured : (propagated instanceof TraceContext t ? t : null));
        span.tag("rag.mode", hybridEnabled ? "hybrid" : "vector");
        String outcome = "success";
        List<Document> parents = List.of();
        try (var ignored = telemetry.scope(span)) {
            // 扩窗条件（2026-09-23 修正）：**任一生效的 rerank 模式**都扩窗到粗召回窗口 topN。
            // 原写法 `isEnabled() && "llm".equals(mode)` 只认 llm —— 那是 llm 是唯一模式时写的；
            // 新增 remote 后该分支拿不到扩窗，候选池退化成 topK(=8)，
            // 直接削弱了"宽召回 + 精排"的设计意图（ADR-25）。
            int k = rerankProperties.isActive() ? rerankProperties.getTopN() : topK;
            List<Document> children = hybridEnabled
                    // 非 hybrid 兜底路径同样"过取 → 过滤 → 截断"：见下方注释，形状必须与 hybrid 一致
                    ? hybridRetrieve(query.text(), k)
                    : vectorStore.similaritySearch(SearchRequest.builder().query(query.text()).topK(k * 3).build())
                          .stream().filter(ParentChildDocumentRetriever::isKnowledgeDoc).limit(k).toList();
            // 兜底过滤（防御未来新增通道）：知识库检索只回文档块。
            // ⚠️ **这道过滤不能是唯一防线** —— 它跑在候选已截断之后，被它剔掉的位子不会补人，
            // 表现为"候选无声变少"。真正的过滤必须在**截断之前**（见 hybridRetrieve 内）。
            children = children.stream().filter(ParentChildDocumentRetriever::isKnowledgeDoc).toList();
            span.tag("rag.candidates", String.valueOf(children.size()));
            parents = children.stream().map(this::toParent).collect(Collectors.toList());
            logRetrieved(query.text(), children);
            if (parents.isEmpty()) outcome = "empty";
        } catch (RuntimeException e) {
            outcome = "degraded";
            span.tag("langfuse.observation.level", "WARNING");
            throw e;
        } finally {
            span.tag("rag.outcome", outcome).tag("rag.results", String.valueOf(parents.size()));
            span.end();
        }
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

    /**
     * 知识库检索只接受**文档块**：排除用户记忆（{@code source=memory}）与已学技能（{@code source=evolution}）。
     *
     * <p><b>为什么抽成一个谓词</b>：这个判断原先在两处各写了一遍内联 lambda，很容易漂移成
     * "过滤标准不一致"。更重要的是 —— 它的**调用位置**决定语义：必须在**截断到 top-k 之前**
     * 调用（见 ADR-40），否则被剔除的候选不会补人，表现为"结果无声变少"。</p>
     */
    private static boolean isKnowledgeDoc(Document d) {
        Object src = d.getMetadata().get("source");
        return !"memory".equals(src) && !"evolution".equals(src);
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
        // ⚠️ 顺序不能反（2026-09-23 修正，ADR-40）：**必须先过滤、后截断**。
        // 反过来的话记忆块会白占候选位，而记忆块数量远多于知识块
        // （实测某查询的向量 top-24 里 memory=20 / knowledge=4，top-8 里 memory=6）——
        // 过滤后知识块可能只剩个位数，甚至 ≤ postprocessor 的 topK，
        // 从而**导致重排在聊天链路上被静默跳过**（实测：hits=5 <= topK=5，Rerank call 0 次）。
        List<Document> merged = fused.values().stream()
                .sorted((a, b) -> Double.compare(b.totalScore(), a.totalScore()))
                .map(r -> r.doc)
                .filter(ParentChildDocumentRetriever::isKnowledgeDoc)
                .limit(topK)
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
                    // 2026-09-05：父子切块改为 overlap 扁平（无 parent_id）——去掉过滤，全块可被关键词命中
                    .append("WHERE (");
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

    /**
     * 中文分词提取关键词（2026-09-04 修复）：原实现按空白/标点切整段——中文无空格，
     * "冷战筑墙怎么办"被当成一个整词 LIKE，关键词通道近乎空转（hybrid 退化根因）。
     * 现用 jieba 对查询分词（SEARCH 细粒度），逐词 LIKE 可命中专有实体（筑墙/冷战/道歉）。
     */
    private List<String> extractKeywords(String query) {
        List<String> out = new java.util.ArrayList<>();
        if (query == null || query.isBlank()) return out;
        try {
            for (com.huaban.analysis.jieba.SegToken tok :
                    JIEBA.process(query, com.huaban.analysis.jieba.JiebaSegmenter.SegMode.SEARCH)) {
                String t = tok.word.trim();
                if (t.length() < 2 || t.length() > 12) continue;      // 中文词一般≥2字；跳过单字防海量命中
                if (STOP_WORDS.contains(t)) continue;
                if (!t.matches("[\\p{IsHan}A-Za-z0-9\\-]+")) continue; // 剔除标点/符号碎片
                out.add(t);
                if (out.size() >= 6) break;
            }
        } catch (Exception e) {
            log.warn("jieba segment failed (fallback raw split): {}", e.getMessage());
        }
        // 兜底：分词结果为空（纯英文/异常）退回按空白切
        if (out.isEmpty()) {
            for (String w : query.split("[\s，,、。]+")) {
                String t = w.trim();
                if (t.length() >= 2 && t.length() <= 12 && !STOP_WORDS.contains(t)) {
                    out.add(t);
                    if (out.size() >= 6) break;
                }
            }
        }
        return out;
    }

    /** jieba 分词器实例（线程安全、词典随 classpath 自动加载） */
    private static final com.huaban.analysis.jieba.JiebaSegmenter JIEBA =
            new com.huaban.analysis.jieba.JiebaSegmenter();

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "的", "了", "吗", "呢", "是", "我", "你", "他", "她", "请", "帮", "给", "在", "不",
            "也", "都", "就", "想", "要", "说", "回答", "写", "列", "出", "具体", "内容", "步骤",
            "怎么", "什么", "如何", "为什么", "应该", "以及", "或者", "一个", "一些", "起来",
            "and", "or", "the", "a", "to", "how", "what", "for");

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
