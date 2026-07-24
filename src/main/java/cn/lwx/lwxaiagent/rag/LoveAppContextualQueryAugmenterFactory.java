package cn.lwx.lwxaiagent.rag;


import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

public class LoveAppContextualQueryAugmenterFactory {


    public static ContextualQueryAugmenter createQueryAugmenter() {

        PromptTemplate promptTemplate = new PromptTemplate("""
                抱歉我只能回答恋爱相关的问题
                """);

        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(false) // If true, returns original query when no relevant context; if false, returns empty string, e.g. user asks about weather, no context found, LLM responds with apology
                .promptTemplate(promptTemplate)
                .build();
    }
}
