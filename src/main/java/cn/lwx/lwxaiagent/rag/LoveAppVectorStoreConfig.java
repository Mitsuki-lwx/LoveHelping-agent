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
 * Configure vector store
 * In-memory based
 * Uses Spring AI's built-in implementation
 */
@Configuration
public class LoveAppVectorStoreConfig {
    @Resource// Inject LoveAppDocumentLoader object
    private LoveAppDocumentLoader loveAppDocumentLoader;


    @Resource
    private MyTokenTextSplitter mytokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Bean// Create a vector store object
    VectorStore LoveAppVectorStore(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {// Create vector store, inject embedding model object
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();// Create vector store object
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();// Load Markdown documents
        //List<Document> splitDocuments = mytokenTextSplitter.splitDocuments(documents);// Split documents
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);// Add keywords
        vectorStore.add(enrichedDocuments);// Add documents
        return vectorStore;
    }
}
