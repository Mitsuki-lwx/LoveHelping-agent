package cn.lwx.lwxaiagent.infrastructure.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Langfuse 接入配置（参考 CodeForge：环境变量三件套）。
 * <p>Langfuse 2.x 认证 = Bearer(publicKey) + X-Langfuse-Signature(HMAC-SHA256(secretKey, body))，
 * 静态 OTLP header 无法签名，故手写 ingestion 上报（见 {@link LangfuseReporter}）。</p>
 * <p>密钥经环境变量注入（AGENTS.md 安全底线），不落明文。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.langfuse")
@Slf4j
public class LangfuseProperties {

    /** 总开关（默认关，配置齐全才启用） */
    private boolean enabled = false;
    /** public key（env: LANGFUSE_PUBLIC_KEY） */
    private String publicKey;
    /** secret key（env: LANGFUSE_SECRET_KEY） */
    private String secretKey;
    /** Langfuse 地址（env: LANGFUSE_HOST，默认 http://localhost:3000） */
    private String host = "http://localhost:3000";

    public boolean isEnabled() {
        return enabled && publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    public boolean getEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
}