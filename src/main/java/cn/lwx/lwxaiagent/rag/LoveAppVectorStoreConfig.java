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
 * 配置向量存储
 *基于内存的
 * 是自带的实现
 */
@Configuration
public class LoveAppVectorStoreConfig {
    @Resource//注入LoveAppDocumentLoader对象
    private LoveAppDocumentLoader loveAppDocumentLoader;


    @Resource
    private MyTokenTextSplitter mytokenTextSplitter;

    @Resource
    private MyKeywordEnricher myKeywordEnricher;

    @Bean//创建一个向量存储对象
    VectorStore LoveAppVectorStore(@Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {//创建一个向量存储对象.注入嵌入模型对象
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();//创建一个向量存储对象
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();//加载Markdown文档
        //List<Document> splitDocuments = mytokenTextSplitter.splitDocuments(documents);//分割文档
        List<Document> enrichedDocuments = myKeywordEnricher.enrichDocuments(documents);//添加关键词
        vectorStore.add(enrichedDocuments);//添加文档
        return vectorStore;
    }
}
