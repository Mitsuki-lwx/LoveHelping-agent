package cn.lwx.lwxaiagent.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 这是pgvector数据库连接属性
 * @author lwx
 *
 */
@Data
@ConfigurationProperties(prefix = "app.pgvector.datasource")
public class PgvectorProperties {
    private String driverClassName;
    private String url;
    private String username;
    private String password;
}