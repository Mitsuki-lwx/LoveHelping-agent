//package cn.lwx.lwxaiagent.demo;
//
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.ai.rag.Query;
//import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//
//
//public class MyMultiQueryExpanderDemo {
//
//
//    private final ChatClient.Builder chatClientBuilder;
//
//    public MyMultiQueryExpanderDemo(ChatModel deepseekChatModel) {
//        this.chatClientBuilder = ChatClient.builder(deepseekChatModel);
//        }
//
//    public List<Query> expand(String query) {
//        MultiQueryExpander queryExpander = MultiQueryExpander.builder()
//                .chatClientBuilder(chatClientBuilder)
//                .numberOfQueries(3)
//                .build();
//        List<Query> queries = queryExpander.expand(new Query("How to run a Spring Boot app?"));
//        return queries;
//    }
//
//}
