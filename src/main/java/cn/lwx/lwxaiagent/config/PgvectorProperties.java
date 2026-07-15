package cn.lwx.lwxaiagent.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.pgvector.datasource")
public class PgvectorProperties {
    private String driverClassName;
    private String url;
    private String username;
    private String password;
}