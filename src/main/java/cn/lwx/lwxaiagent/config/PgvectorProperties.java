package cn.lwx.lwxaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <h1>Pgvector 数据库连接属性配置类</h1>
 *
 * <p>通过 {@code @ConfigurationProperties} 将 YAML/Properties 配置文件中的
 * {@code app.pgvector.datasource.*} 前缀的属性自动绑定到这个类的字段上。</p>
 *
 * <p><b>Pgvector 是什么？</b>PostgreSQL 的一个向量扩展插件，使 PostgreSQL 支持向量存储和相似度搜索。
 * 在这个项目中用于 RAG（检索增强生成）场景，存储文档的向量嵌入（Embedding），
 * 实现语义相似度搜索——用户提问时，找到最相关的知识片段注入给大模型。</p>
 *
 * <p><b>配置示例（application.yml）：</b></p>
 * <pre>{@code
 * app:
 *   pgvector:
 *     datasource:
 *       driver-class-name: org.postgresql.Driver
 *       url: jdbc:postgresql://localhost:5432/lwx_ai_agent
 *       username: postgres
 *       password: 123456
 * }</pre>
 *
 * <p><b>绑定原理：</b>Spring Boot 在启动时，{@code @ConfigurationPropertiesScan}
 * （在主启动类上声明）会扫描到此类，然后根据 {@code prefix = "app.pgvector.datasource"} 前缀，
 * 将配置文件中对应属性值通过 setter 方法（Lombok {@code @Data} 自动生成）注入进来。</p>
 *
 * @author lwx
 */
@Data  // Lombok：自动生成 getter、setter、toString、equals、hashCode
@ConfigurationProperties(prefix = "app.pgvector.datasource")  // 绑定配置文件中 app.pgvector.datasource 前缀的属性
public class PgvectorProperties {

    /**
     * JDBC 驱动类名，例如 {@code org.postgresql.Driver}
     */
    private String driverClassName;

    /**
     * 数据库连接 URL，例如 {@code jdbc:postgresql://localhost:5432/lwx_ai_agent}
     */
    private String url;

    /**
     * 数据库用户名
     */
    private String username;

    /**
     * 数据库密码
     */
    private String password;
}