package cn.lwx.lwxaiagent.tools;

import cn.lwx.lwxaiagent.retrieval.HybridRetrievalService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <h2>知识库搜索工具类</h2>
 * <p>
 * 负责从恋爱关系知识库中检索相关内容，通过 Spring AI 的 {@link Tool @Tool} 注解将检索能力
 * 暴露给大语言模型（LLM）进行函数调用（Function Calling）。
 * </p>
 *
 * <h3>检索后端（ADR-1 收敛后）</h3>
 * <p>
 * 检索栈已收敛为 pgvector 单一实现：优先使用 {@link HybridRetrievalService}（pgvector 门面），
 * 不可用时降级为直接调用 pgvector 相似度检索。原 Milvus + ES + RRF 混合检索已移除。
 * </p>
 *
 * @author lwx
 * @see HybridRetrievalService
 */
@Slf4j
@Component
public class KnowledgeSearchTool {

    /**
     * PostgreSQL pgvector 向量存储实例，用于兜底路径的语义相似度检索。
     * 通过 {@code @Resource} 注解按名称注入（Bean 名 PgVectorVectorStore）。
     */
    @Resource
    private VectorStore PgVectorVectorStore;

    /**
     * 统一检索服务（ADR-1 收敛后后端为 pgvector）。
     * 使用 {@code required = false}：检索服务不可用时知识库工具降级为空结果，不影响对话。
     */
    @Autowired(required = false)
    private HybridRetrievalService hybridRetrievalService;

    /**
     * <h3>搜索恋爱关系知识库</h3>
     *
     * @param query 用户的搜索查询文本（通常是用户向 LLM 提出的情感问题）
     * @param topK  期望返回的结果数量，默认值 3，最大不超过 10
     * @return 格式化的检索结果字符串，每条结果以 "•" 开头，多条结果之间以两个换行符分隔；
     *         未检索到任何匹配结果时返回 "No relevant knowledge found for: " + 查询文本
     */
    @Tool(description = "Search the romantic relationship knowledge base for dating/relationship/marriage advice. Use this when the user asks for emotional advice, dating tips, or relationship problem solving. Returns relevant Q&A entries from the expert knowledge base.")
    public String searchKnowledge(
            @ToolParam(description = "Search query for knowledge base") String query,
            @ToolParam(description = "Number of results to return (default 3, max 10)") Integer topK) {
        // 校验并限制 topK 参数：null 或非正数取默认值 3，超过 10 则截断为 10
        int k = (topK != null && topK > 0) ? Math.min(topK, 10) : 3;

        // 获取当前租户 ID（单租户期仅用于日志，不参与过滤，ADR-13）
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            log.debug("Searching knowledge for tenant={}", tenantId);
        }

        List<Document> results;
        if (hybridRetrievalService != null) {
            // 统一检索门面（pgvector 向量检索，ADR-1）
            results = hybridRetrievalService.search(query, k, tenantId);
        } else {
            // 兜底：直接走 pgvector 相似度检索
            results = PgVectorVectorStore.similaritySearch(SearchRequest.builder().query(query).topK(k).build());
        }

        // 无结果时返回友好提示
        if (results.isEmpty()) {
            return "No relevant knowledge found for: " + query;
        }
        // 将检索到的文档列表格式化为易读的文本块：每条结果以 "•" 开头，双换行分隔
        return results.stream()
                .map(doc -> "\u2022 " + doc.getText())
                .collect(Collectors.joining("\n\n"));
    }
}
