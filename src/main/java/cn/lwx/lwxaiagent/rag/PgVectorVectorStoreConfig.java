package cn.lwx.lwxaiagent.rag;

import cn.hutool.core.collection.ListUtil;
import cn.lwx.lwxaiagent.config.PgvectorProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <h2>PgVector（PostgreSQL + pgvector 插件）向量库配置类</h2>
 *
 * <p>这是一个 Spring 配置类（用 @Configuration 标注），
 * 负责创建和初始化<b>基于 PostgreSQL + pgvector 插件的持久化向量库</b>。</p>
 *
 * <h3>什么是 pgvector？</h3>
 * <p>pgvector 是 PostgreSQL 的开源向量扩展插件，它使得 PostgreSQL 数据库
 * 能够存储和检索高维向量，支持高效的向量相似度搜索（ANN 近似最近邻搜索）。
 * 相比内存向量库（SimpleVectorStore），pgvector 具有以下优势：</p>
 * <ul>
 *   <li><b>数据持久化</b>：数据存储在 PostgreSQL 磁盘上，应用重启不会丢失</li>
 *   <li><b>生产可用</b>：支持事务、备份、主从复制等 PostgreSQL 企业级特性</li>
 *   <li><b>大规模存储</b>：可存储百万级向量，远超内存的限制</li>
 *   <li><b>高效检索</b>：支持 HNSW 索引，实现毫秒级的近似最近邻搜索</li>
 * </ul>
 *
 * <h3>与 LoveAppVectorStoreConfig（内存向量库）的关系</h3>
 * <p>两者都是 VectorStore 接口的实现，是互斥的选择：
 * SimpleVectorStore 适合开发/测试，PgVectorStore 适合生产环境。</p>
 *
 * <h3>技术参数说明</h3>
 * <table border="1">
 *   <tr><th>参数</th><th>值</th><th>含义</th></tr>
 *   <tr><td>distanceType</td><td>COSINE_DISTANCE</td><td>使用余弦距离度量相似度，值越小越相似</td></tr>
 *   <tr><td>indexType</td><td>HNSW</td><td>使用 HNSW（层次化可导航小世界图）索引，
 *       在搜索速度和召回率之间取得最佳平衡</td></tr>
 *   <tr><td>initializeSchema</td><td>true</td><td>自动创建 pgvector 所需的数据库表结构</td></tr>
 *   <tr><td>maxDocumentBatchSize</td><td>10000</td><td>单批次最多处理的文档数</td></tr>
 * </table>
 *
 * <h3>HNSW 索引原理简介</h3>
 * <p>HNSW（Hierarchical Navigable Small World）是当前最先进的近似最近邻搜索算法之一。
 * 它构建多层图结构，搜索时从顶层快速定位大致区域，逐层向下精细搜索，
 * 在搜索速度和召回率之间取得业界领先的平衡。</p>
 *
 * <h3>幂等性设计</h3>
 * <p>在入库前先查询 vector_store 表的记录数。如果表中已有数据，
 * 则跳过初始化步骤，避免每次重启都重复导入相同的文档。</p>
 *
 * @author lwx
 * @since 1.0
 * @see org.springframework.ai.vectorstore.pgvector.PgVectorStore
 * @see LoveAppVectorStoreConfig
 */
@Slf4j
@Configuration
public class PgVectorVectorStoreConfig {

    /**
     * 注入文档加载器
     * 负责从 classpath:document/ 目录下加载所有 Markdown 文件
     */
    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    /**
     * 注入 PostgreSQL pgvector 相关的配置属性
     * 包含数据库连接 URL、用户名、密码、驱动类名等
     */
    @Resource
    private PgvectorProperties pgvectorProperties;

    /**
     * <h3>创建并初始化 PgVector 向量库 Bean</h3>
     *
     * <p>该方法是整个 PostgreSQL 向量库初始化的核心，执行以下步骤：</p>
     *
     * <h3>步骤一：建立数据库连接</h3>
     * <ol>
     *   <li>通过 DataSourceBuilder 创建 PostgreSQL 数据源（DataSource）</li>
     *   <li>使用配置属性中的 URL、用户名、密码和驱动类名进行连接</li>
     *   <li>基于 DataSource 创建 JdbcTemplate，用于执行 SQL 操作</li>
     * </ol>
     *
     * <h3>步骤二：创建 PgVectorStore 实例</h3>
     * <ol>
     *   <li>传入 JdbcTemplate（数据库操作）和 EmbeddingModel（文本转向量）</li>
     *   <li>配置距离类型为 COSINE_DISTANCE（余弦距离），这是最常用的相似度度量：
     *     <ul>
     *       <li>余弦距离 = 1 - 余弦相似度</li>
     *       <li>值越接近 0 表示越相似，值越接近 2 表示越不相似</li>
     *     </ul>
     *   </li>
     *   <li>配置索引类型为 HNSW（层次化可导航小世界图索引）：
     *     <ul>
     *       <li>IVFFlat：基于聚类的索引，适合静态数据集</li>
     *       <li><b>HNSW</b>：基于图的索引，搜索速度更快，适合动态数据集</li>
     *     </ul>
     *   </li>
     *   <li>配置自动初始化数据库 Schema（initializeSchema=true）：
     *     自动创建 vector_store 表及其向量索引</li>
     *   <li>配置表名为 "vector_store"，Schema 为 "public"</li>
     *   <li>配置最大批次大小为 10000</li>
     * </ol>
     *
     * <h3>步骤三：数据初始化（幂等操作）</h3>
     * <ol>
     *   <li>查询 vector_store 表中的记录数</li>
     *   <li><b>如果表为空</b>（count == 0 或 null）：
     *     <ul>
     *       <li>调用 LoveAppDocumentLoader 加载 Markdown 文档</li>
     *       <li>对文档内容进行去重（基于文本内容使用 HashSet）</li>
     *       <li>将去重后的文档分批写入向量库（每批最多 10 条，遵守 DashScope API 限制）</li>
     *     </ul>
     *   </li>
     *   <li><b>如果表已有数据</b>（count > 0）：
     *     跳过初始化，避免重复导入</li>
     * </ol>
     *
     * <h3>批量写入说明</h3>
     * <p>使用 Hutool 的 ListUtil.partition 将文档列表切分为每批 10 条。
     * 原因：阿里百炼 DashScope API 的 Embedding 接口单次调用限制最多处理 10 条文档，
     * 分批调用可以避免 API 报错并平滑网络流量。</p>
     *
     * <h3>去重逻辑</h3>
     * <p>使用 HashSet 按文档的文本内容（doc.getText()）去重。
     * 相同文本内容的文档只保留一份，避免向量库中存在冗余数据。</p>
     *
     * @param embeddingModel 向量嵌入模型（由 @Qualifier 指定为 dashscopeEmbeddingModel），
     *                       负责将文本转换为向量，来自阿里百炼
     * @return 初始化好的 PgVectorStore 实例，已连接到 PostgreSQL 并可能已导入文档数据
     */
    @Bean
    public VectorStore PgVectorVectorStore(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        // ---- 步骤一：创建 PostgreSQL 数据源 ----
        DataSource pgDataSource = DataSourceBuilder.create()
                .url(pgvectorProperties.getUrl())               // 数据库连接 URL
                .username(pgvectorProperties.getUsername())     // 数据库用户名
                .password(pgvectorProperties.getPassword())     // 数据库密码
                .driverClassName(pgvectorProperties.getDriverClassName()) // JDBC 驱动类名
                .build();

        // 基于数据源创建 JdbcTemplate，用于执行 SQL 操作
        JdbcTemplate pgJdbcTemplate = new JdbcTemplate(pgDataSource);

        // ---- 步骤二：创建 PgVectorStore 实例 ----
        PgVectorStore vectorStore = PgVectorStore.builder(pgJdbcTemplate, embeddingModel)
                // 使用余弦距离作为相似度度量（值越小越相似）
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                // 使用 HNSW 索引实现高效近似最近邻搜索
                .indexType(PgVectorStore.PgIndexType.HNSW)
                // 自动创建数据库表结构（vector_store 表）
                .initializeSchema(true)
                // 使用 public schema
                .schemaName("public")
                // 向量数据存储的表名
                .vectorTableName("vector_store")
                // 单次批量处理的最大文档数
                .maxDocumentBatchSize(10000)
                .build();

        // ---- 步骤三：数据初始化（幂等操作） ----
        // 若 app.rag.reindex=true 则强制重建（用于新文档导入后）
        if ("true".equalsIgnoreCase(System.getProperty("app.rag.reindex", "false"))) {
            log.info("Reindex flag set, clearing vector store and reloading...");
            pgJdbcTemplate.execute("DELETE FROM vector_store");
        }

        // 查询表中已有记录数
        Integer count = pgJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store", Integer.class);

        // ---- 父子索引迁移检测（ADR-15）：表里若为旧格式（整篇文档，无 parent_id）则重建 ----
        if (count != null && count > 0) {
            Integer parentCount = pgJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM vector_store WHERE metadata->>'parent_id' IS NOT NULL",
                    Integer.class);
            if (parentCount == null || parentCount == 0) {
                log.warn("Vector store contains legacy whole-doc chunks, rebuilding with parent-child index...");
                pgJdbcTemplate.update("DELETE FROM vector_store");
                count = 0;
            }
        }

        // 只在表为空时执行数据导入（幂等性保证）
        if (count != null && count == 0) {
            // 加载所有 Markdown 文档
            List<Document> documents = loveAppDocumentLoader.loadMarkdowns();

            // 按文本内容去重：相同内容的多个文档只保留一份
            Set<String> seen = new HashSet<>();
            List<Document> uniqueDocs = new ArrayList<>();
            for (Document doc : documents) {
                // seen.add() 返回 false 表示元素已存在（即重复），返回 true 表示首次添加
                if (seen.add(doc.getText())) {
                    uniqueDocs.add(doc);
                }
            }

            // 父子索引切分（ADR-15）：整篇 → 父块 → 子块（子块带 parent_id/parent_text）
            List<Document> children = new ParentChildDocumentTransformer().apply(uniqueDocs);
            documents = children;
            log.info("Parent-child split: {} docs -> {} child chunks", uniqueDocs.size(), children.size());

            // 将子块分批写入向量库
            // DashScope API 限制：单次调用最多处理 10 条文档，所以按 10 条一批进行切分
            List<List<Document>> batches = ListUtil.partition(documents, 10);
            for (List<Document> batch : batches) {
                // 每批 10 条文档调用一次 add 方法
                // EmbeddingModel 会自动将文本转换为向量并写入数据库
                vectorStore.add(batch);
            }

            log.info("Successfully loaded {} chunks into vector store", documents.size());
        } else {
            // 表中已有数据，跳过导入
            log.info("Vector store already has {} records, skipping initialization", count);
        }

        return vectorStore;
    }
}