package cn.lwx.lwxaiagent.rag;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h2>自定义关键词提取 / 元数据丰富器</h2>
 *
 * <p>这是一个 Spring 管理的组件类（用 @Component 标注），
 * 在 RAG 文档处理流程中负责<b>使用 LLM 为文档自动提取关键词</b>，
 * 并将关键词信息添加到文档的元数据中。</p>
 *
 * <h3>为什么需要关键词丰富？</h3>
 * <p>在 RAG 检索中，仅靠向量相似度搜索有时不够精确。通过为文档添加关键词元数据，
 * 可以在检索时提供额外的匹配维度：</p>
 * <ul>
 *   <li><b>向量搜索</b>：基于语义相似度，适合模糊匹配和同义词</li>
 *   <li><b>关键词匹配</b>：基于精确术语匹配，适合专业术语和特定概念</li>
 *   <li><b>两者结合</b>：提升检索的召回率和精确率</li>
 * </ul>
 *
 * <h3>工作原理</h3>
 * <p>KeywordMetadataEnricher 是 Spring AI 框架提供的内置组件，其工作流程如下：</p>
 * <ol>
 *   <li>将文档文本发送给指定的 ChatModel（LLM）</li>
 *   <li>LLM 分析文档内容，提取出最能代表文档主题的关键词</li>
 *   <li>将提取的关键词列表添加到文档的 metadata 中（key 为 "keywords" 或类似字段）</li>
 *   <li>后续检索时，可以使用这些关键词进行过滤或加权</li>
 * </ol>
 *
 * <h3>在 RAG 流程中的位置</h3>
 * <pre>
 * 文档加载 → [关键词丰富：本类] → 文档分割 → 向量嵌入 → 存入向量库
 *               ↑
 *         使用 LLM 提取关键词并注入到元数据中
 * </pre>
 *
 * @author lwx
 * @since 1.0
 * @see org.springframework.ai.model.transformer.KeywordMetadataEnricher
 */
@Slf4j
@Component
public class MyKeywordEnricher {

    /**
     * 注入主聊天模型（LlmGateway，ADR-7——原 DashScope 已停用）
     * 该 ChatModel 用于调用 LLM 来分析和提取文档中的关键词
     */
    @Resource
    private ChatModel chatModel;

    /**
     * <h3>为文档列表中的每个文档提取并注入关键词元数据</h3>
     *
     * <p>该方法创建一个 KeywordMetadataEnricher 实例，配置每个文档提取 5 个关键词，
     * 然后对所有输入文档执行关键词提取操作。</p>
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>创建 KeywordMetadataEnricher 实例，配置如下：
     *     <ul>
     *       <li><b>chatModel</b>：使用百炼的 dashScopeChatModel 作为 LLM</li>
     *       <li><b>keywordCount(5)</b>：每个文档提取 5 个关键词</li>
     *     </ul>
     *   </li>
     *   <li>调用 enricher.apply(documents)，对每个文档：
     *     <ul>
     *       <li>将文档文本发送给 LLM</li>
     *       <li>LLM 返回 5 个最能代表文档内容的关键词</li>
     *       <li>关键词被添加到文档的 metadata Map 中</li>
     *     </ul>
     *   </li>
     *   <li>返回丰富后的文档列表</li>
     * </ol>
     *
     * <h3>为什么选 5 个关键词？</h3>
     * <p>5 是一个平衡值：太少（如1-2个）可能遗漏重要概念，
     * 太多（如10个以上）可能引入噪声。5个关键词通常能较好地覆盖
     * 文档的核心主题，同时保持元数据的精简。</p>
     *
     * @param documents 原始文档列表，每个 Document 包含文本内容和原始元数据
     * @return 丰富后的文档列表，每个 Document 的元数据中新增了 LLM 提取的关键词信息
     */
    public List<Document> enrichDocuments(List<Document> documents) {
        // 创建关键词元数据丰富器
        KeywordMetadataEnricher enricher = KeywordMetadataEnricher.builder(chatModel)
                // 每个文档提取 5 个关键词，平衡覆盖度和精准度
                .keywordCount(5)
                .build();
        try {
            // 对所有文档执行关键词提取，关键词被注入到每个文档的 metadata 中
            return enricher.apply(documents);
        } catch (Exception e) {
            // 降级：LLM 不可用时跳过关键词提取（关键词是检索辅助信息，缺失不阻断文档入库/启动）
            log.warn("Keyword enrichment failed, skipping (docs={}): {}", documents.size(), e.getMessage());
            return documents;
        }
    }
}