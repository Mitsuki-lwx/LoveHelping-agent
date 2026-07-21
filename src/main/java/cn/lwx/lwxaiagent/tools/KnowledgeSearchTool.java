package cn.lwx.lwxaiagent.tools;

import cn.lwx.lwxaiagent.retrieval.HybridRetrievalProperties;
import cn.lwx.lwxaiagent.retrieval.HybridRetrievalService;
import cn.lwx.lwxaiagent.retrieval.MilvusVectorRetriever;
import cn.lwx.lwxaiagent.retrieval.RetrieverType;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeSearchTool {

    @Resource
    private VectorStore PgVectorVectorStore;

    @Resource
    private HybridRetrievalProperties ragProps;

    @Autowired(required = false)
    private MilvusVectorRetriever milvusRetriever;

    @Autowired(required = false)
    private HybridRetrievalService hybridRetrievalService;

    @Tool(description = "Search the romantic relationship knowledge base for dating/relationship/marriage advice. Use this when the user asks for emotional advice, dating tips, or relationship problem solving. Returns relevant Q&A entries from the expert knowledge base.")
    public String searchKnowledge(
            @ToolParam(description = "Search query for knowledge base") String query,
            @ToolParam(description = "Number of results to return (default 3, max 10)") Integer topK) {
        int k = (topK != null && topK > 0) ? Math.min(topK, 10) : 3;

        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            log.debug("Searching knowledge for tenant={}", tenantId);
        }

        List<Document> results;
        if (ragProps.getType() == RetrieverType.hybrid && hybridRetrievalService != null) {
            results = hybridRetrievalService.search(query, k, tenantId);
        } else if (ragProps.getType() == RetrieverType.hybrid && milvusRetriever != null) {
            results = milvusRetriever.search(query, k, tenantId);
        } else {
            SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(k);
            if (tenantId != null) {
                builder.filterExpression(new FilterExpressionBuilder().eq("tenantId", tenantId).build());
            }
            results = PgVectorVectorStore.similaritySearch(builder.build());
        }

        if (results.isEmpty()) {
            return "No relevant knowledge found for: " + query;
        }
        return results.stream()
                .map(doc -> "\u2022 " + doc.getText())
                .collect(Collectors.joining("\n\n"));
    }
}
