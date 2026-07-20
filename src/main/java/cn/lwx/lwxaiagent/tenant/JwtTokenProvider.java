package cn.lwx.lwxaiagent.tenant;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与验证工具。
 * <p>
 * Token 结构：
 * <pre>{@code
 * {
 *   "sub": "user_001",         // 用户 ID
 *   "tenantId": "tenant_01",  // 租户 ID
 *   "role": "USER",            // 角色
 *   "exp": 1760649600          // 过期时间
 * }
 * }</pre>
 * <p>
 * 不做刷新 token、不做黑名单、不做多设备管理。
 * 简单签发 + 验证，够用就行。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret:lwx-ai-agent-secret-key-need-at-least-32-chars}")
    private String secret;

    @Value("${jwt.expiration-ms:86400000}")  // 默认 24 小时
    private long expirationMs;

    private SecretKey key;

    @PostConstruct
    public void init() {
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        log.info("JWT provider initialized (expiration={}ms)", expirationMs);
    }

    /**
     * 签发 JWT。
     *
     * @param userId    用户 ID
     * @param tenantId  租户 ID
     * @param role      角色（USER / ADMIN）
     * @return JWT 字符串
     */
    public String generateToken(String userId, String tenantId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim("tenantId", tenantId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 验证并解析 JWT。
     *
     * @param token JWT 字符串
     * @return Claims（含 sub/tenantId/role/exp）
     * @throws Exception token 无效或过期
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从 Claims 中提取租户信息并注入 TenantContext */
    public void injectContext(Claims claims) {
        String userId = claims.getSubject();
        String tenantId = claims.get("tenantId", String.class);
        String role = claims.get("role", String.class);
        TenantContext.set(tenantId, userId, role);
    }

    /** 快速验证 token 是否有效（不抛异常） */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
