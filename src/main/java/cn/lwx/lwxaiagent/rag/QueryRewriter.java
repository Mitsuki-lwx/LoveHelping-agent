package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Component;

@Component
public class QueryRewriter {

    private final QueryTransformer queryTransformer;

    public QueryRewriter(ChatModel deepseekChatModel) {
        ChatClient.Builder builder = ChatClient.builder(deepseekChatModel);
        queryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(builder)
                .build();
    }

    public String doRewrite(String prompt) {
        Query query = new Query( prompt);
        Query rewrittenQuery = queryTransformer.transform(query);
        return rewrittenQuery.text();
    }


}
