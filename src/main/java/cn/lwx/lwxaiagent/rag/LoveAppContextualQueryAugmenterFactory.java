package cn.lwx.lwxaiagent.rag;


import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

public class LoveAppContextualQueryAugmenterFactory {


    public static ContextualQueryAugmenter createQueryAugmenter() {

        PromptTemplate promptTemplate = new PromptTemplate("""
                抱歉我只能回答恋爱相关的问题
                """);

        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(false) //选true就会在没有相关上下文时返回原始查询并且交给大模型，选false则会返回空字符串，比如用户问“今天天气怎么样”，因为没有相关上下文，所以返回空字符串，交给大模型后就会回答“抱歉我只能回答恋爱相关的问题”
                .promptTemplate(promptTemplate)
                .build();
    }
}
