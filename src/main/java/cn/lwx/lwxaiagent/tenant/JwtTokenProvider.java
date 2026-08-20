package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.tenant.context.TenantContext;
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
 * <h1>JWT（JSON Web Token）签发与验证工具</h1>
 * <p>
 * 本类是系统安全认证的核心组件，负责 JWT 的<strong>签发（生成）</strong>和
 * <strong>验证（解析）</strong>。所有需要身份认证的 API 请求都必须携带有效的 JWT，
 * 系统通过解析 JWT 来识别用户身份和所属租户。
 * </p>
 *
 * <h2>JWT 是什么？</h2>
 * <p>
 * JSON Web Token（RFC 7519）是一种紧凑的、URL 安全的令牌格式，
 * 用于在各方之间安全地传输声明（claims）。JWT 由三部分组成，
 * 以点号（.）分隔：
 * </p>
 * <ol>
 *   <li><b>Header（头部）</b>：包含签名算法信息，如 {@code {"alg":"HS256","typ":"JWT"}}</li>
 *   <li><b>Payload（载荷）</b>：包含实际的声明数据（claims），
 *       如用户 ID、租户 ID、角色等</li>
 *   <li><b>Signature（签名）</b>：使用密钥对 Header 和 Payload 的哈希值进行签名，
 *       防止令牌被篡改</li>
 * </ol>
 *
 * <h2>Token 结构（Payload 声明）</h2>
 * <pre>{@code
 * {
 *   "sub": "user_001",         // 主题（Subject）—— 用户 ID，JWT 标准字段
 *   "tenantId": "tenant_01",  // 自定义声明 —— 租户 ID，用于多租户数据隔离
 *   "role": "USER",            // 自定义声明 —— 角色（USER / ADMIN）
 *   "iat": 1750000000,         // 签发时间（Issued At），Unix 时间戳
 *   "exp": 1750086400          // 过期时间（Expiration），Unix 时间戳
 * }
 * }</pre>
 *
 * <h2>签名算法 —— HMAC-SHA256</h2>
 * <p>
 * 使用 HMAC-SHA256（HS256）对称签名算法。这意味着<strong>签发和验证使用同一个密钥</strong>。
 * 密钥的安全性至关重要 —— 任何人获得密钥后都可以伪造有效的 JWT。
 * 因此：
 * </p>
 * <ul>
 *   <li>密钥应通过环境变量或加密的配置文件注入，<strong>绝不要硬编码在代码中</strong></li>
 *   <li>密钥长度至少为 256 位（32 字节），本系统默认密钥仅用于开发环境</li>
 *   <li>生产环境务必更换为足够复杂的随机密钥</li>
 * </ul>
 *
 * <h2>安全边界说明</h2>
 * <p>
 * 本实现是<strong>简化版</strong>JWT 方案，不包含以下高级特性：
 * </p>
 * <ul>
 *   <li>刷新令牌（Refresh Token）机制 —— Token 过期后用户需要重新登录</li>
 *   <li>令牌黑名单（Token Blacklist）—— 无法主动撤销已签发的 Token</li>
 *   <li>多设备会话管理 —— 不跟踪用户的登录设备数</li>
 *   <li>非对称签名（RS256/ES256）—— 仅使用对称 HS256</li>
 * </ul>
 * <p>
 * 对于当前系统的安全需求（轻量级 AI Agent 应用），这是"够用"的设计。
 * 如需更高安全级别，建议引入 Spring Security OAuth2 Resource Server。
 * </p>
 *
 * <h2>初始化流程</h2>
 * <p>
 * 通过 {@link PostConstruct @PostConstruct} 注解，在 Spring 容器完成依赖注入后
 * 自动调用 {@link #init()} 方法，将配置的密钥字符串转换为 {@link SecretKey} 对象。
 * 这样可以确保密钥在整个应用生命周期中只初始化一次。
 * </p>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see TenantContext 租户上下文（JWT 解析后注入的目标）
 * @see io.jsonwebtoken.Jwts JJWT 库的核心入口类
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /**
     * JWT 签名密钥的原始字符串。
     * 从配置文件中的 {@code jwt.secret} 项读取，默认值仅用于开发环境。
     *
     * <p><b>安全提醒</b>：HMAC-SHA256 要求密钥长度至少为 256 位（32 字节）。
     * 如果提供的密钥不足 32 字节，JJWT 库会抛出 {@link io.jsonwebtoken.security.WeakKeyException}。
     * 默认值 "lwx-ai-agent-secret-key-need-at-least-32-chars" 恰好满足 32 字节的最低要求。</p>
     */
    @Value("${jwt.secret:lwx-ai-agent-secret-key-need-at-least-32-chars}")
    private String secret;

    /**
     * JWT 的有效期（毫秒）。
     * 从配置文件中的 {@code jwt.expiration-ms} 项读取，默认值为 86400000 毫秒（24 小时）。
     *
     * <p>过期时间的选择是一个安全性与用户体验之间的权衡：
     * <ul>
     *   <li>过期时间越短，Token 被盗后的风险窗口越小，但用户需要频繁重新登录</li>
     *   <li>过期时间越长，用户体验越好，但安全风险也相应增加</li>
     * </ul>
     * 24 小时是一个相对平衡的选择。</p>
     */
    @Value("${jwt.expiration-ms:86400000}")  // 默认 24 小时
    private long expirationMs;

    /**
     * 从密钥字符串生成的 HMAC-SHA256 签名密钥对象。
     * 在 {@link #init()} 方法中初始化，整个应用生命周期内保持不变。
     */
    private SecretKey key;

    /**
     * <h3>初始化 JWT 密钥</h3>
     * <p>
     * 在 Spring 容器完成依赖注入后自动调用（由 {@link PostConstruct @PostConstruct} 触发）。
     * 将配置文件中的密钥字符串转换为 JJWT 库所需的 {@link SecretKey} 对象。
     * </p>
     *
     * <h4>密钥转换过程</h4>
     * <ol>
     *   <li>将密钥字符串按 UTF-8 编码转换为字节数组</li>
     *   <li>调用 {@link Keys#hmacShaKeyFor(byte[])} 创建 HMAC-SHA256 密钥对</li>
     *   <li>若密钥长度不足 256 位，此步骤会抛出 {@code WeakKeyException}</li>
     * </ol>
     *
     * @throws io.jsonwebtoken.security.WeakKeyException 当配置的密钥长度不足 256 位时抛出
     */
    @PostConstruct
    public void init() {
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        log.info("JWT provider initialized (expiration={}ms)", expirationMs);
    }

    /**
     * <h3>签发（生成）JWT Token</h3>
     * <p>
     * 根据用户身份信息生成一个签名的 JWT 字符串。
     * 生成的 Token 可在后续请求中通过 HTTP Authorization 头传递，
     * 用于证明用户身份。
     * </p>
     *
     * <h4>Token 生成步骤</h4>
     * <ol>
     *   <li>获取当前时间作为签发时间（iat）</li>
     *   <li>计算过期时间 = 当前时间 + 配置的有效期</li>
     *   <li>构建 JWT：设置主题（sub）、自定义声明（claims）、签发时间和过期时间</li>
     *   <li>使用 HMAC-SHA256 密钥签名</li>
     *   <li>压缩为紧凑的 URL 安全字符串</li>
     * </ol>
     *
     * <h4>在 HTTP 请求中使用 Token</h4>
     * <pre>{@code
     * Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyXzAwMSJ9.xxx
     * }</pre>
     *
     * @param userId   用户 ID，将作为 JWT 的 {@code sub}（Subject）声明
     * @param tenantId 租户 ID，将作为自定义声明 {@code tenantId}，
     *                 用于后续请求中的多租户数据隔离
     * @param role     角色标识，如 {@code "USER"} 或 {@code "ADMIN"}，
     *                 将作为自定义声明 {@code role}，可用于权限控制
     * @return 签名的 JWT 字符串，格式为 {@code header.payload.signature}
     */
    public String generateToken(String userId, String tenantId, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId)               // 设置主题（用户 ID）
                .claim("tenantId", tenantId)   // 设置租户 ID 自定义声明
                .claim("role", role)           // 设置角色自定义声明
                .issuedAt(now)                 // 设置签发时间
                .expiration(expiry)            // 设置过期时间
                .signWith(key)                 // 使用 HMAC-SHA256 密钥签名
                .compact();                    // 压缩为字符串
    }

    /**
     * <h3>验证并解析 JWT Token</h3>
     * <p>
     * 对传入的 JWT 字符串进行签名验证和声明解析。
     * <strong>验证失败（签名不符、Token 过期、格式错误等）会直接抛出异常。</strong>
     * </p>
     *
     * <h4>验证内容</h4>
     * <ol>
     *   <li>Token 格式是否正确（是否包含两个点号、各部分是否为合法的 Base64）</li>
     *   <li>签名是否匹配（使用配置的 HMAC-SHA256 密钥验证，
     *       防止 Token 被篡改）</li>
     *   <li>是否已过期（比较 exp 声明与当前时间）</li>
     *   <li>签发时间是否合理（iat 不能是未来时间，除非允许时钟偏差）</li>
     * </ol>
     *
     * <h4>安全原理</h4>
     * <p>
     * JWT 的安全性完全依赖签名验证。由于签名使用只有服务端知道的密钥生成：
     * <ul>
     *   <li>攻击者无法伪造有效的 JWT（不知道密钥）</li>
     *   <li>攻击者无法篡改 JWT 中的声明内容（签名会失效）</li>
     *   <li>但攻击者可以<strong>窃取</strong>他人的有效 JWT 并冒充使用，
     *       因此必须通过 HTTPS 传输，防止中间人攻击</li>
     * </ul>
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return {@link Claims} 对象，包含 Token 中的所有声明（sub、tenantId、role、exp 等）
     * @throws io.jsonwebtoken.ExpiredJwtException      当 Token 已过期时抛出
     * @throws io.jsonwebtoken.security.SignatureException 当签名验证失败时抛出
     * @throws io.jsonwebtoken.MalformedJwtException    当 Token 格式错误时抛出
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)          // 设置验证密钥
                .build()                   // 构建解析器
                .parseSignedClaims(token)  // 解析并验证
                .getPayload();             // 获取 Claims 载荷
    }

    /**
     * <h3>从 JWT Claims 中提取信息并注入到租户上下文</h3>
     * <p>
     * 将解析后的 JWT 声明数据写入 {@link TenantContext}（基于 {@link ThreadLocal}），
     * 使当前请求的后续处理链路（Controller、Service、Repository）都能通过
     * {@link TenantContext#getTenantId()} 等方法获取当前用户和租户信息。
     * </p>
     *
     * <h4>注入的字段</h4>
     * <ul>
     *   <li>{@code userId}：从 JWT 的 {@code sub}（Subject）声明提取</li>
     *   <li>{@code tenantId}：从 JWT 的自定义声明 {@code tenantId} 提取</li>
     *   <li>{@code role}：从 JWT 的自定义声明 {@code role} 提取</li>
     * </ul>
     *
     * <h4>ThreadLocal 的作用</h4>
     * <p>
     * 使用 {@link ThreadLocal} 存储租户信息意味着：
     * <ul>
     *   <li>每个 HTTP 请求由同一个线程处理（在 Tomcat 线程池模型中），
     *       因此 ThreadLocal 中的数据在整个请求处理期间都可用</li>
     *   <li>不同请求的线程之间数据完全隔离，不会出现串数据问题</li>
     *   <li>请求结束后<strong>必须</strong>调用 {@link TenantContext#clear()} 清理，
     *       否则在 Tomcat 线程池复用线程时会造成数据泄漏</li>
     * </ul>
     *
     * @param claims JWT 的 Claims 对象，由 {@link #parseToken(String)} 返回
     */
    public void injectContext(Claims claims) {
        String userId = claims.getSubject();
        String tenantId = claims.get("tenantId", String.class);
        String role = claims.get("role", String.class);
        TenantContext.set(tenantId, userId, role);
    }

    /**
     * <h3>快速验证 Token 是否有效（不抛出异常）</h3>
     * <p>
     * 与 {@link #parseToken(String)} 不同，本方法<strong>捕获所有异常</strong>，
     * 返回一个布尔值表示 Token 是否有效。适用于不需要具体错误信息的场景，
     * 如简单的"是否登录"判断。
     * </p>
     *
     * <h4>返回值含义</h4>
     * <ul>
     *   <li>{@code true}：Token 格式正确、签名有效且未过期</li>
     *   <li>{@code false}：Token 无效——可能是格式错误、签名不符、已过期等任意原因</li>
     * </ul>
     *
     * <h4>安全提示</h4>
     * <p>
     * 本方法不区分失败原因（是过期、签名错误还是格式错误），
     * 统一返回 false。这种设计遵循了<strong>不泄露过多错误信息</strong>的安全原则——
     * 防止攻击者通过不同的错误消息探测系统信息。
     * </p>
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return {@code true} 表示 Token 有效，{@code false} 表示无效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
