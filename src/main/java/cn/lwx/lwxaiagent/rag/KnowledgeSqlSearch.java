package cn.lwx.lwxaiagent.rag;

import cn.lwx.lwxaiagent.config.PgvectorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库的 SQL 查询入口（2026-09-24，ADR-43）。
 *
 * <p><b>为什么要有这个类</b>：知识块与用户记忆**共用同一张 {@code vector_store} 表、同一个 ANN 索引**，
 * 而记忆（461 行）全是本领域对话摘要 —— 实测向量 top-60 里 49 条是记忆、知识块只有 11 条。
 * 把"取候选"与"过滤来源"拆成两步（先 top-k 再在 Java 层过滤）等于**把知识块挤出去**；
 * 所以两条通道都改成**在同一条 SQL 里过滤 + 取数**。</p>
 *
 * <p>顺带的好处：源过滤条件从此**只有这一处**（原先向量通道没有、关键词通道也没有，
 * 靠 Java 层兜底），不会再漂移。</p>
 *
 * <p>⚠️ 这里绕开了 Spring AI 的 {@code VectorStore} 抽象（因为需要把过滤下推到 ANN 查询里）——
 * 将来换向量库要同步改本类。{@link ParentChildDocumentRetriever} 末尾仍保留一道 Java 层过滤
 * 作为兜底，防将来新增通道时漏过滤。</p>
 */
@Slf4j
@Component
public class KnowledgeSqlSearch {

    /**
     * ⛔ 知识检索的源过滤条件 / 行映射**都不再在本类里定义** ——
     * 它们在 {@link VectorRowMapper}（**唯一实现**）。抽出去的理由见 ADR-69：集成测试层不启动
     * Spring 上下文，private 方法测不到；而 {@code COALESCE} 陷阱与三参构造正是 ADR-46 那一类
     * 静默事故的高风险面，必须有容器级测试守着。
     */
    private final JdbcTemplate pg;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper json = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeSqlSearch(PgvectorProperties pgvectorProperties, EmbeddingModel embeddingModel) {
        // 自建 pg JdbcTemplate（不注册为容器 bean，避免与 MySQL 默认 JdbcTemplate 按类型注入歧义）
        DataSource pgDataSource = DataSourceBuilder.create()
                .url(pgvectorProperties.getUrl())
                .username(pgvectorProperties.getUsername())
                .password(pgvectorProperties.getPassword())
                .driverClassName(pgvectorProperties.getDriverClassName())
                .build();
        this.pg = new JdbcTemplate(pgDataSource);
        this.embeddingModel = embeddingModel;
    }

    /**
     * 测试专用构造器：直接注入 JdbcTemplate。
     *
     * <p>存在的唯一理由：生产路径的 JdbcTemplate 是**上面自建**的（避免容器里按类型注入歧义），
     * 单测注入不进去 —— 而 {@link VectorRowMapper#KNOWLEDGE_ONLY} 那个 COALESCE 陷阱是本轮最大的回归风险，
     * 必须有一个能**捕获真正执行的 SQL** 的防线。生产构造器已标 {@code @Autowired}，Spring 不会选这个。</p>
     */
    KnowledgeSqlSearch(JdbcTemplate pg, EmbeddingModel embeddingModel) {
        this.pg = pg;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 向量通道：<b>过滤 + 取最近邻在同一条 SQL 里</b> → ANN 直接在知识子集上取够 k 条。
     *
     * <p>对比修复前：{@code similaritySearch(topK*3)} 取回的 60 条里有 49 条是记忆，
     * 过滤后知识块只剩 11 条，还得靠关键词通道补料。</p>
     */
    public List<Document> byVector(String query, int k) {
        return pg.query(
                "SELECT " + VectorRowMapper.SELECT_COLUMNS + " FROM vector_store WHERE " + VectorRowMapper.KNOWLEDGE_ONLY
                        + " ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, row) -> VectorRowMapper.toDocument(rs, json), vectorLiteral(query), k);
    }

    /**
     * 关键词通道（pg_trgm 式的 {@code LIKE} 打分）。**同样必须过滤来源** ——
     * 实测 {@code content LIKE} 的命中里记忆占比不低（「沟通」95/174 = 55%、「冷战」21/52 = 40%），
     * 它们会先占掉 RRF 名次、再被 Java 层丢掉，等于白白稀释知识块的位置。
     */
    List<Document> byKeyword(List<String> words, int k) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT id, content, metadata::text FROM vector_store ")
                .append("WHERE ").append(VectorRowMapper.KNOWLEDGE_ONLY).append(" AND (");
        List<Object> params = new ArrayList<>();
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
        params.add(k);
        return pg.query(sql.toString(), (rs, row) -> VectorRowMapper.toDocument(rs, json), params.toArray());
    }

    /**
     * 按 id 回算相似度（只用于检索可观测的打分日志，不参与检索决策）。
     * 调用方需先用 {@code app.rag.log-score} 关掉它 —— 它要额外做一次 embedding。
     */
    Map<String, Double> scores(String query, List<String> ids) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        // id 取自库内 UUID，非外部输入
        String in = String.join(",", ids.stream().map(id -> "'" + id + "'").toList());
        // 用**块** lambda（不能写成表达式 lambda）：JdbcTemplate.query 有多个重载，
        // 表达式 lambda 会同时匹配 RowCallbackHandler 与 ResultSetExtractor → 编译期"引用不明确"。
        pg.query(
                "SELECT id::text, 1 - (embedding <=> ?::vector) AS score FROM vector_store WHERE id::text IN (" + in + ")",
                rs -> {
                    out.put(rs.getString(1), rs.getDouble(2));
                }, vectorLiteral(query));
        return out;
    }

    /** 查询向量 → pgvector 字面量。三条查询共用，避免各写一遍拼装逻辑。 */
    private String vectorLiteral(String query) {
        float[] vec = embeddingModel.embed(query);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        return sb.append("]").toString();
    }

}
