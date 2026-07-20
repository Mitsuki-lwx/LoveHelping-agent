package cn.lwx.lwxaiagent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

///**
// *这是将deepSeekChatModel作为默认的ChatModel
// * @author lwx
// *
// */
//@Configuration
//public class ChatModelConfig {
//
//    @Bean
//    @Primary
//    public ChatModel primaryChatModel(@Qualifier("deepSeekChatModel") ChatModel chatModel) {
//        return chatModel;
//    }
//}
