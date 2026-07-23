package cn.lwx.lwxaiagent.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端配置。
 * 将 RestClient 和 ElasticsearchClient 作为 Spring Bean 统一管理，
 * 供 {@link ESKeywordRetriever} 和 {@link DatabaseSchemaInitializer} 共用。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
public class ElasticsearchClientConfig {

    @Bean(destroyMethod = "close")
    public RestClient esRestClient(HybridRetrievalProperties props) {
        var es = props.getHybrid().getEs();
        log.info("Creating ES client: {}", es.getUris());
        return RestClient.builder(HttpHost.create(es.getUris())).build();
    }

    @Bean
    public ElasticsearchClient esClient(RestClient esRestClient) {
        return new ElasticsearchClient(new RestClientTransport(esRestClient, new JacksonJsonpMapper()));
    }
}
