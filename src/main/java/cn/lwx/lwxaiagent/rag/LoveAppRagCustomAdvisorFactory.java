package cn.lwx.lwxaiagent.rag;


import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

public class LoveAppRagCustomAdvisorFactory {


    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        //创建一个过滤器
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("status", status)
                .build();

        //创建一个向量存储检索器
        VectorStoreDocumentRetriever vectorStoreDocumentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)//设置向量存储
                .filterExpression(expression)//设置过滤器
                .similarityThreshold(0.5)//设置相似度阈值
                .topK(3)//设置返回的文档数量
                .build();


        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(vectorStoreDocumentRetriever)
                .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createQueryAugmenter())//添加查询增强器
                .build();
    }
}
