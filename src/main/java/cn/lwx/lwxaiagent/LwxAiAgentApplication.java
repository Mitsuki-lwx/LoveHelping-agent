package cn.lwx.lwxaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LwxAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LwxAiAgentApplication.class, args);
    }

}
