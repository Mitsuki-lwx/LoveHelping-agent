package cn.lwx.lwxaiagent.retrieval;

/**
 * 检索器类型枚举。
 * pgvector：使用 PostgreSQL pgvector 插件进行向量检索（默认）。
 * hybrid：使用 Milvus + Elasticsearch 进行混合检索（第一期新增）。
 * <p>
 * 通过 application.yml 中的 rag.type 配置切换：
 * {@code rag.type: pgvector} 或 {@code rag.type: hybrid}
 */
public enum RetrieverType {
    pgvector,
    hybrid
}
