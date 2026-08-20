package cn.lwx.lwxaiagent.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <h2>混合检索配置属性映射类</h2>
 *
 * <p>这是一个 Spring Boot 配置属性类（用 @ConfigurationProperties 标注），
 * 负责将 application.yml 中以 {@code rag} 为前缀的配置项自动映射为 Java 对象。</p>
 *
 * <h3>配置优先级</h3>
 * <p>{@code type} 字段决定使用哪种检索模式：</p>
 * <ul>
 *   <li><b>pgvector（默认）</b>：使用 PgVectorVectorStoreConfig 中配置的
 *       PostgreSQL + pgvector 向量库进行单一向量检索</li>
 *   <li><b>hybrid</b>：使用 Milvus（向量检索）+ Elasticsearch（关键词检索）
 *       进行混合检索，通过 RRF 融合两个维度的结果</li>
 * </ul>
 *
 * <h3>示例配置（application.yml）</h3>
 * <pre>{@code
 * rag:
 *   # 检索器类型：pgvector（默认）| hybrid
 *   type: hybrid
 *   hybrid:
 *     es:
 *       uris: http://localhost:9200                          # ES 服务地址
 *       index: love_knowledge                                # ES 索引名称
 *     milvus:
 *       host: 172.28.23.117                                  # Milvus 服务地址
 *       port: 19530                                          # Milvus 服务端口
 *       collection-name: love_knowledge                      # Collection 名称
 *       vector-dim: 1024                                     # 向量维度
 *     rrf:
 *       k: 60                                                # RRF 平滑参数
 *       top-k: 10                                            # 最终返回的结果数
 * }</pre>
 *
 * @author lwx
 * @since 1.0
 * @see HybridRetrievalConfig
 */
@ConfigurationProperties(prefix = "rag")
public class HybridRetrievalProperties {

    /**
     * 检索器类型枚举
     * 通过 application.yml 中的 rag.type 配置切换
     */
    private RetrieverType type = RetrieverType.pgvector;

    /**
     * hybrid 模式下的详细配置容器
     * 包含 ES（关键词检索）、Milvus（向量检索）和 RRF（融合排序）三个子组件
     */
    private Hybrid hybrid = new Hybrid();

    // ========== getter / setter ==========
    public RetrieverType getType() { return type; }
    public void setType(RetrieverType type) { this.type = type; }
    public Hybrid getHybrid() { return hybrid; }
    public void setHybrid(Hybrid hybrid) { this.hybrid = hybrid; }

    /**
     * <h3>Hybrid 模式下三个子组件的配置容器</h3>
     *
     * <p>混合检索（Hybrid）模式使用两个独立的检索引擎：</p>
     * <ol>
     *   <li><b>ES（Elasticsearch）</b>：BM25 关键词检索，擅长精确匹配</li>
     *   <li><b>Milvus</b>：向量语义检索，擅长语义理解</li>
     *   <li><b>RRF（Reciprocal Rank Fusion）</b>：融合排序，综合两个维度的结果</li>
     * </ol>
     */
    public static class Hybrid {
        /** Elasticsearch 配置 */
        private Es es = new Es();
        /** Milvus 向量数据库配置 */
        private Milvus milvus = new Milvus();
        /** RRF（Reciprocal Rank Fusion）融合排序参数 */
        private Rrf rrf = new Rrf();
        public Es getEs() { return es; }
        public void setEs(Es es) { this.es = es; }
        public Milvus getMilvus() { return milvus; }
        public void setMilvus(Milvus milvus) { this.milvus = milvus; }
        public Rrf getRrf() { return rrf; }
        public void setRrf(Rrf rrf) { this.rrf = rrf; }
    }

    /**
     * <h3>Elasticsearch 配置类</h3>
     *
     * <p>ES 用于 BM25 关键词检索。BM25 是信息检索领域经典的关键词匹配算法，
     * 擅长精确匹配专有名词、术语和数字等，与向量搜索的语义理解形成互补。</p>
     */
    public static class Es {
        /**
         * ES 服务地址
         * 格式：http://host:port，如 http://localhost:9200
         */
        private String uris = "http://localhost:9200";
        /**
         * ES 索引名称，相当于关系数据库中的"表名"
         * 同一索引下的文档共享相同的 Mapping 定义
         */
        private String index = "love_knowledge";
        public String getUris() { return uris; }
        public void setUris(String uris) { this.uris = uris; }
        public String getIndex() { return index; }
        public void setIndex(String index) { this.index = index; }
    }

    /**
     * <h3>Milvus 向量数据库配置类</h3>
     *
     * <p>Milvus 是专为向量搜索设计的开源向量数据库，支持高效的
     * 近似最近邻（ANN）搜索。在混合检索中负责<b>向量语义检索</b>。</p>
     */
    public static class Milvus {
        /** Milvus 服务主机地址 */
        private String host = "localhost";
        /** Milvus 服务端口（默认 19530） */
        private int port = 19530;
        /** Collection 名称（相当于关系数据库中的"表名"） */
        private String collectionName = "love_knowledge";
        /**
         * 向量维度
         * 必须与 EmbeddingModel 的输出维度完全一致。
         * 例如：阿里百炼 DashScope text-embedding-v2 输出 1024 维向量
         */
        private int vectorDim = 1024;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getCollectionName() { return collectionName; }
        public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
        public int getVectorDim() { return vectorDim; }
        public void setVectorDim(int vectorDim) { this.vectorDim = vectorDim; }
    }

    /**
     * <h3>RRF（Reciprocal Rank Fusion）融合排序参数配置类</h3>
     *
     * <p>RRF 是一种基于排名的多路搜索结果融合算法。
     * 核心公式：<b>rrf_score(d) = SUM(1 / (k + rank_i(d)))</b></p>
     *
     * <p>其中：</p>
     * <ul>
     *   <li><b>d</b>：被评估的文档</li>
     *   <li><b>k</b>：平滑参数（本类中的 k），影响排名靠后的文档被提升的程度</li>
     *   <li><b>rank_i(d)</b>：文档 d 在第 i 个检索器中的排名（从 0 开始）</li>
     * </ul>
     *
     * <h3>k 值选择指南</h3>
     * <ul>
     *   <li><b>k=60（默认）</b>：适用于从每个通道获取约 50-100 条结果的情况</li>
     *   <li><b>k=30</b>：适用于从每个通道获取约 20 条结果的情况，排名靠前的文档权重更大</li>
     *   <li>k 值越大，排名靠后的文档被提升得越多，融合结果越"民主"</li>
     *   <li>k 值越小，排名靠前的文档权重越大，融合结果越"精英"</li>
     * </ul>
     *
     * <h3>为什么不用加权平均？</h3>
     * <p>Milvus 的余弦相似度范围是 [0, 1]，而 ES 的 BM25 分数无上限。
     * 两种分数的尺度不同，无法直接加权。RRF 只依赖排名，不受分数尺度影响，
     * 因此更适合融合不同来源的检索结果。</p>
     */
    public static class Rrf {
        /**
         * RRF 公式中的平滑参数 k
         * 影响排名靠后的文档被提升的程度。默认值 60 是业界经验值
         */
        private int k = 60;
        /**
         * 最终返回给用户的结果数量
         * 经过 RRF 融合排序后只保留得分最高的 topK 条文档
         */
        private int topK = 10;
        public int getK() { return k; }
        public void setK(int k) { this.k = k; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }
}
