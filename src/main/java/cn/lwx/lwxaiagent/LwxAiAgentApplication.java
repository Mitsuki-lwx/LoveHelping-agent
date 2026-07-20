package cn.lwx.lwxaiagent;

import cn.lwx.lwxaiagent.retrieval.HybridRetrievalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(HybridRetrievalProperties.class)
public class LwxAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LwxAiAgentApplication.class, args);
    }

}
