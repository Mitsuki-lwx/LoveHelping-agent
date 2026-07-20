package cn.lwx.lwxaiagent.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 混合检索配置映射类。
 * 将 application.yml 中以 "rag" 为前缀的配置映射为 Java 对象。
 * <p>
 * 示例配置：
 * <pre>{@code
 * rag:
 *   type: hybrid
 *   hybrid:
 *     es:
 *       uris: http://localhost:9200
 *       index: love_knowledge
 *     milvus:
 *       host: 172.28.23.117
 *       port: 19530
 *       vector-dim: 1024
 *     rrf:
 *       k: 60
 *       topK: 10
 * }</pre>
 */
@ConfigurationProperties(prefix = "rag")
public class HybridRetrievalProperties {

    /** 检索器类型：pgvector（默认）| hybrid */
    private RetrieverType type = RetrieverType.pgvector;

    /** hybrid 模式下的详细配置（ES / Milvus / RRF） */
    private Hybrid hybrid = new Hybrid();

    public RetrieverType getType() { return type; }
    public void setType(RetrieverType type) { this.type = type; }
    public Hybrid getHybrid() { return hybrid; }
    public void setHybrid(Hybrid hybrid) { this.hybrid = hybrid; }

    /**
     * Hybrid 模式下三个子组件的配置容器。
     * ES（关键词检索）+ Milvus（向量检索）+ RRF（融合排序）
     */
    public static class Hybrid {
        private Es es = new Es();
        private Milvus milvus = new Milvus();
        private Rrf rrf = new Rrf();
        public Es getEs() { return es; }
        public void setEs(Es es) { this.es = es; }
        public Milvus getMilvus() { return milvus; }
        public void setMilvus(Milvus milvus) { this.milvus = milvus; }
        public Rrf getRrf() { return rrf; }
        public void setRrf(Rrf rrf) { this.rrf = rrf; }
    }

    /** Elasticsearch 配置 */
    public static class Es {
        /** ES 服务地址 */
        private String uris = "http://localhost:9200";
        /** 索引名称 ，就是相当于表名*/
        private String index = "love_knowledge";
        public String getUris() { return uris; }
        public void setUris(String uris) { this.uris = uris; }
        public String getIndex() { return index; }
        public void setIndex(String index) { this.index = index; }
    }

    /** Milvus 向量数据库配置 */
    public static class Milvus {
        private String host = "localhost";
        private int port = 19530;
        private String collectionName = "love_knowledge";
        /** 向量维度，需与 EmbeddingModel 的输出维度一致 */
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

    /** RRF（Reciprocal Rank Fusion）融合排序参数 */
    public static class Rrf {
        /** RRF 公式中的 k 值，影响排名靠后的文档被提升的程度 */
        private int k = 60;
        /** 最终返回给用户的结果数量 */
        private int topK = 10;
        public int getK() { return k; }
        public void setK(int k) { this.k = k; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }
}
