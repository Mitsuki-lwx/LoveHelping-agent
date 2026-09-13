package cn.lwx.lwxaiagent.config;

import cn.lwx.lwxaiagent.infrastructure.ai.LlmGatewayProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import java.net.http.HttpClient;

import java.time.Duration;

/** Socket deadlines bound SDK work even when application Future cancellation cannot interrupt it. */
@Configuration
public class AiHttpClientConfig {
    @Bean RestClientCustomizer boundedRestClients(LlmGatewayProperties p) {
        return builder -> {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(p.getConnectTimeoutMs()));
            factory.setReadTimeout(Duration.ofMillis(p.getAttemptTimeoutMs()));
            builder.requestFactory(factory);
        };
    }
    @Bean WebClientCustomizer boundedWebClients(LlmGatewayProperties p) {
        return builder -> {
            var connector = new JdkClientHttpConnector(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(p.getConnectTimeoutMs())).build());
            connector.setReadTimeout(Duration.ofMillis(p.getAttemptTimeoutMs()));
            builder.clientConnector(connector).codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(1024 * 1024));
        };
    }
}
