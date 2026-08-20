package cn.lwx.lwxaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * <h2>LoveApp 内存向量库配置类</h2>
 *
 * <p>这是一个 Spring 配置类（用 @Configuration 标注），
 * 负责创建和初始化<b>基于内存的简单向量库（SimpleVectorStore）</b>。</p>
 *
 * <h3>什么是向量库（VectorStore）？</h3>
 * <p>向量库是 RAG 系统的核心存储组件。文档通过 EmbeddingModel 转换为
 * 高维向量（一组浮点数），存储在向量库中。检索时，用户查询也被转换为向量，
 * 然后通过<b>余弦相似度</b>计算找出最相关的文档。</p>
 *
 * <h3>SimpleVectorStore 的特点</h3>
 * <ul>
 *   <li><b>基于内存</b>：所有数据存储在 JVM 堆内存中，应用重启后数据丢失</li>
 *   <li><b>无需外部依赖</b>：不需要安装 PostgreSQL/PgVector、Milvus 等外部数据库</li>
 *   <li><b>适合场景</b>：开发调试、小规模文档集（几百到几千条）、功能验证</li>
 *   <li><b>不适合场景</b>：生产环境、大规模文档集、需要持久化存储的场景</li>
 * </ul>
 *
 * <h3>与 PgVectorVectorStoreConfig 的关系</h3>
 * <p>本类使用 SimpleVectorStore（内存），PgVectorVectorStoreConfig 使用 PgVectorStore（数据库）。
 * 两者是互斥的——根据配置决定使用哪一个。本类适用于轻量级或开发场景。</p>
 *
 * <h3>RAG 全流程中的位置</h3>
 * <pre>
 * Markdown 文件 → LoveAppDocumentLoader(加载) → MyKeywordEnricher(关键词提取)
 *     → EmbeddingModel(向量嵌入) → SimpleVectorStore(本类，存储)
 *     → VectorStoreDocumentRetriever(检索) → LLM 生成回答
 * </pre>
 *
 * @author lwx
 * @since 1.0
 * @see SimpleVectorStore
 * @see PgVectorVectorStoreConfig
 */
@Configuration
public class LoveAppVectorStoreConfig {

    /**
     * 注入文档加载器（使用 @Resource 按名称/类型注入）
     * 负责从 classpath:document/ 目录下加载所有 Markdown 文件
     */
    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    /**
     * 注入自定义的 Token 文本分割器（使用 @Resource 注入）
     * 可以将长文档按 Token 数量分割为多个小段落，提高检索精度
     * 注意：当前代码中分割功能被注释掉了，直接使用原始文档
     */
    @Resource
    private MyTokenTextSplitter mytokenTextSplitter;

    /**
     * 注入关键词提取器（使用 @Resource 注入）
     * 使用 LLM 从文档中提取关键词，增强文档的元数据信息，提升检索效果
     */
    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    /**
     * <h3>创建并初始化基于内存的向量库 Bean</h3>
     *
     * <p>此方法执行完整的 RAG 文档入库流程：</p>
     *
     * <ol>
     *   <li><b>创建 SimpleVectorStore</b>：基于内存的向量库，使用指定的 EmbeddingModel
     *       将文档文本转换为向量表示</li>
     *   <li><b>加载 Markdown 文档</b>：调用 LoveAppDocumentLoader.loadMarkdowns()
     *       从 classpath:document/ 目录加载所有 .md 文件</li>
     *   <li><b>关键词丰富</b>：调用 MyKeywordEnricher 为每个文档提取关键词，
     *       关键词信息被添加到文档的元数据中，可以提升检索时对关键术语的匹配能力</li>
     *   <li><b>存入向量库</b>：将丰富后的文档列表添加到 SimpleVectorStore 中。
     *       存入时 EmbeddingModel 会自动将文本转为向量</li>
     * </ol>
     *
     * <h3>关于被注释的分割步骤</h3>
     * <p>代码中原有的 mytokenTextSplitter.splitDocuments(documents) 被注释掉了。
     * 这意味着当前配置下文档<b>不做分割</b>，每个文档整体作为一个检索单位。
     * 如果文档较长，可以考虑取消注释以启用文本分割，将长文档切分为更小的段落，
     * 提高检索的精准度。</p>
     *
     * <h3>EmbeddingModel 说明</h3>
     * <p>使用 @Qualifier("dashscopeEmbeddingModel") 指定注入百炼的 EmbeddingModel。
     * EmbeddingModel 负责将文本转换为高维向量（如1024维浮点数组），
     * 相似的文本在向量空间中距离更近。这是实现语义搜索的关键组件。</p>
     *
     * @param embeddingModel 向量嵌入模型（由 @Qualifier 指定为 dashscopeEmbeddingModel），
     *                       负责将文本转换为向量表示，来自阿里百炼
     * @return 初始化好的 SimpleVectorStore 实例，已加载并嵌入所有 Markdown 文档
     */
    @Bean
    VectorStore LoveAppVectorStore(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        // 创建基于内存的简单向量库，嵌入模型负责将文本转为向量
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        // 从 classpath:document/ 目录加载所有 Markdown 文档
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        // （可选）使用 Token 文本分割器将长文档分割为小段，当前被注释掉
        //List<Document> splitDocuments = mytokenTextSplitter.splitDocuments(documents);
        // 使用 LLM 为文档提取关键词并注入到元数据中
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);
        // 将文档存入向量库，EmbeddingModel 自动将文本转换为向量并存储
        vectorStore.add(enrichedDocuments);
        return vectorStore;
    }
}
