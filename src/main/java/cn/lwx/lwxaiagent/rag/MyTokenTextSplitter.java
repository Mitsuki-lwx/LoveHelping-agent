package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class MyTokenTextSplitter {

    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }

    public List<Document> splitCustomized(List<Document> documents) {
        // 自定义分词器，参数分别是最大长度，最小长度，最大分词数，最大分词数，是否保留标点符号，保留的标点符号
        TokenTextSplitter splitter = new TokenTextSplitter(1000, 400, 10, 5000, true, List.of('.', '?', '!', '\n'));
        return splitter.apply(documents);
    }
}
