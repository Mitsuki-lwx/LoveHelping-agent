package cn.lwx.lwxaiagent.rag;


import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

public class LoveAppRagCustomAdvisorFactory {


    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        // Create a filter expression
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("status", status)
                .build();

        // Create a vector store retriever
        VectorStoreDocumentRetriever vectorStoreDocumentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)// Set vector store
                .filterExpression(expression)// Set filter expression
                .similarityThreshold(0.5)// Set similarity threshold
                .topK(3)// Set number of returned documents
                .build();


        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(vectorStoreDocumentRetriever)
                .queryAugmenter(LoveAppContextualQueryAugmenterFactory.createQueryAugmenter())// Add query augmenter
                .build();
    }
}
