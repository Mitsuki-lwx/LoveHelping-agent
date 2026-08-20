package cn.lwx.lwxaiagent.retrieval;

/**
 * <h2>检索器类型枚举</h2>
 *
 * <p>定义了本系统支持的两种检索器类型，用于在 application.yml 中通过
 * {@code rag.type} 配置项切换。</p>
 *
 * <h3>两种检索模式对比</h3>
 * <table border="1">
 *   <tr><th>特性</th><th>pgvector（默认）</th><th>hybrid</th></tr>
 *   <tr>
 *     <td><b>检索引擎</b></td>
 *     <td>PostgreSQL + pgvector 插件</td>
 *     <td>Milvus + Elasticsearch</td>
 *   </tr>
 *   <tr>
 *     <td><b>检索方式</b></td>
 *     <td>单一向量检索（余弦相似度）</td>
 *     <td>向量检索 + BM25 关键词检索 + RRF 融合</td>
 *   </tr>
 *   <tr>
 *     <td><b>中文分词</b></td>
 *     <td>无（仅向量）</td>
 *     <td>ES 的 IK 分词器</td>
 *   </tr>
 *   <tr>
 *     <td><b>适用场景</b></td>
 *     <td>中小规模数据集，快速部署，开发测试</td>
 *     <td>大规模数据集，需要高召回率和精确率的生产环境</td>
 *   </tr>
 *   <tr>
 *     <td><b>外部依赖</b></td>
 *     <td>仅需 PostgreSQL（通常已有）</td>
 *     <td>需要额外安装 Milvus 和 Elasticsearch</td>
 *   </tr>
 *   <tr>
 *     <td><b>维护复杂度</b></td>
 *     <td>低，单点维护</td>
 *     <td>中，需要维护两个独立的检索引擎</td>
 *   </tr>
 * </table>
 *
 * <h3>配置方式</h3>
 * <p>在 application.yml 中设置：</p>
 * <pre>{@code
 * # 使用 PostgreSQL pgvector 向量检索（默认）
 * rag:
 *   type: pgvector
 *
 * # 使用 Milvus + ES 混合检索
 * rag:
 *   type: hybrid
 * }</pre>
 *
 * <h3>扩展性</h3>
 * <p>未来如需添加新的检索模式（如纯 Milvus、纯 ES、或其他检索引擎的组合），
 * 只需在此枚举中添加新的值并在对应的配置类中使用 @ConditionalOnProperty 注解。</p>
 *
 * @author lwx
 * @since 1.0
 * @see HybridRetrievalProperties
 * @see HybridRetrievalConfig
 */
public enum RetrieverType {

    /**
     * pgvector 向量检索模式（默认）
     *
     * 使用 PostgreSQL 的 pgvector 扩展插件进行向量存储和检索。
     * 实现了基于 Cosine Distance（余弦距离）的近似最近邻搜索。
     * 对应配置类：PgVectorVectorStoreConfig
     */
    pgvector,

    /**
     * 混合检索模式
     *
     * 使用 Milvus（向量语义检索）+ Elasticsearch（BM25 关键词检索）
     * 两路检索 + RRF（Reciprocal Rank Fusion）融合排序。
     * 相比单一的 pgvector 检索，混合检索能同时利用语义理解和关键词匹配的优势。
     * 对应配置类：HybridRetrievalConfig
     */
    hybrid
}
