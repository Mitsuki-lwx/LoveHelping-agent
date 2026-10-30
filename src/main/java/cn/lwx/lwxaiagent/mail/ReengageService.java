package cn.lwx.lwxaiagent.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * <h1>回访邮件（V37，ADR-92）—— 合规优先的"主动触达"</h1>
 *
 * <p>与验证码/找回邮件不同：那是用户**来找我们**，这是**我们去找用户**。所以门槛更高：</p>
 * <ol>
 *   <li><b>显式同意</b>（{@code users.reengage_consent}，默认 0）；</li>
 *   <li><b>邮箱已验证</b>（没验证过的地址可能根本不是他的）；</li>
 *   <li><b>未成年人一律不发</b>（不做例外 —— 情感类触达对未成年人的风险不对称）；</li>
 *   <li><b>频率克制</b>：沉默 ≥ {@code silence-days} 才发，同一人 ≥ {@code min-interval-days} 才再发；</li>
 *   <li><b>一键退订</b>：无状态签名链接，无需登录即可生效。</li>
 * </ol>
 *
 * <h2>⛔ 为什么默认 {@code enabled=false}</h2>
 * <p>部署不该等于"开始给用户发信"。开启是一个**产品决定**（模板、频率、话术都过审之后），
 * 不该藏在"代码上线"里。默认关 ⇒ 上线后**一封都不会发**，这是唯一安全的默认值。</p>
 *
 * <h2>失败语义</h2>
 * <p>单封失败**不中断整批**（记 warn 继续），且 <b>不写台账</b> —— 写了就会被当成"已发"，
 * 14 天内不再重试，等于把失败永久化。</p>
 */
@Slf4j
@Service
public class ReengageService {

    private final MailSenderPort mailSender;
    private final JdbcTemplate jdbc;

    private final boolean enabled;
    private final int silenceDays;
    private final int minIntervalDays;
    private final int dailyLimit;
    private final String subject;
    private final String bodyTemplate;
    private final String secret;
    private final String cron;

    public ReengageService(MailSenderPort mailSender,
                           JdbcTemplate jdbc,
                           @Value("${app.reengage.enabled:false}") boolean enabled,
                           @Value("${app.reengage.silence-days:7}") int silenceDays,
                           @Value("${app.reengage.min-interval-days:14}") int minIntervalDays,
                           @Value("${app.reengage.daily-limit:20}") int dailyLimit,
                           @Value("${app.reengage.subject:还在这里，等你把话说完}") String subject,
                           @Value("${app.reengage.body:}") String bodyTemplate,
                           @Value("${app.reengage.token-secret:${jwt.secret:}}") String secret,
                           @Value("${app.reengage.cron:0 30 10 * * *}") String cron) {
        this.mailSender = mailSender;
        this.jdbc = jdbc;
        this.enabled = enabled;
        // 下限保护：配置写错时宁可少发（silence/interval 至少 1 天，daily-limit 至少 1 封）
        this.silenceDays = Math.max(1, silenceDays);
        this.minIntervalDays = Math.max(1, minIntervalDays);
        this.dailyLimit = Math.max(1, Math.min(200, dailyLimit));
        this.subject = subject;
        this.bodyTemplate = bodyTemplate;
        this.secret = secret;
        this.cron = cron;
    }

    /** 每天 10:30 发一轮（白天，不在深夜打扰）。cron 可配：运维/验证需要"现在就发一轮"时改它。 */
    @Scheduled(cron = "${app.reengage.cron:0 30 10 * * *}")
    public void scheduledRun() {
        if (!enabled) {
            return;   // 默认路径：一封不发
        }
        try {
            int sent = runOnce();
            if (sent > 0) log.info("Reengage: 本次发送 {} 封", sent);
        } catch (RuntimeException e) {
            log.warn("Reengage 调度失败（不影响其他功能）：{}", e.getMessage());
        }
    }

    /**
     * 发一轮，返回成功封数。
     *
     * <p>资格条件**全部**满足才发，且一次最多 {@code daily-limit} 封（有界）。</p>
     */
    public int runOnce() {
        if (!enabled) return 0;
        if (secret == null || secret.isBlank()) {
            // ⛔ 没有 secret 就签不了退订链接 —— 宁可不发，也不能发一封"用户没法退订"的信
            log.warn("Reengage: 未配置 token-secret 且 jwt.secret 为空，跳过（否则退订链接无法验签）");
            return 0;
        }
        List<Candidate> candidates = candidates();
        int sent = 0;
        for (Candidate c : candidates) {
            if (sent >= dailyLimit) break;
            try {
                mailSender.send(c.email(), subject, render(c));
                logSent(c.userId(), c.email());
                sent++;
            } catch (RuntimeException e) {
                // 单封失败不中断整批，且**不写台账**（否则失败会被当成"已发"，14 天内不再重试）
                log.warn("Reengage 单封发送失败（继续下一封）：user={}, err={}", c.userId(), e.getMessage());
            }
        }
        return sent;
    }

    /**
     * 候选人查询（资格判定全在 SQL 里，便于审计）。
     *
     * <p>⛔ 占位符顺序必须与参数顺序一致：本 SQL 的 {@code ?} 全部在 WHERE 里、按时间先后排列。</p>
     */
    List<Candidate> candidates() {
        String sql = """
                SELECT u.username, u.email
                FROM users u
                WHERE u.reengage_consent = 1
                  AND u.email IS NOT NULL AND u.email <> ''
                  AND u.email_verified_at IS NOT NULL
                  AND COALESCE(u.minor_mode, 0) = 0
                  AND COALESCE(u.enabled, 1) = 1
                  AND COALESCE(
                        (SELECT MAX(m.created_at) FROM message m WHERE m.user_id = u.username AND m.deleted = 0),
                        u.created_at
                      ) < ?
                  AND NOT EXISTS (
                        SELECT 1 FROM reengage_mail_log l
                        WHERE l.user_id = u.username AND l.sent_at >= ?
                      )
                ORDER BY u.id
                LIMIT ?
                """;
        LocalDateTime silenceBefore = LocalDateTime.now().minusDays(silenceDays);
        LocalDateTime intervalSince = LocalDateTime.now().minusDays(minIntervalDays);
        return jdbc.query(sql,
                (rs, i) -> new Candidate(rs.getString("username"), rs.getString("email")),
                silenceBefore, intervalSince, dailyLimit);
    }

    private void logSent(String userId, String email) {
        jdbc.update("INSERT INTO reengage_mail_log (tenant_id, user_id, email, kind) VALUES ('default', ?, ?, 'REENGAGE')",
                userId, email);
    }

    /** 模板渲染：只替换两个占位符，**不引用任何聊天内容**（触达邮件不该成为隐私泄露面）。 */
    String render(Candidate c) {
        String tpl = (bodyTemplate == null || bodyTemplate.isBlank())
                ? "你好{username}：\n\n上次你在这里停在了半句话上。想接上时随时回来——我一直在这儿。\n\n如果不想再收到这类信，点这里退订：{unsubscribeUrl}\n"
                : bodyTemplate;
        return tpl.replace("{username}", c.userId())
                  .replace("{unsubscribeUrl}", unsubscribeUrl(c.userId()));
    }

    /** 退订链接（无状态签名；用户可能在别的设备点，所以必须免登录）。 */
    public String unsubscribeUrl(String userId) {
        return "/api/auth/reengage/unsubscribe?u=" + urlEncode(userId) + "&t=" + sign(userId);
    }

    /**
     * 签名 = HMAC-SHA256(userId, secret) 的 hex 前 16 字节（32 hex 字符）。
     * <p>⛔ 常量时间比较，避免按字节比较泄露时序。</p>
     */
    public String sign(String userId) {
        // ⛔ secret 为空时**返回空串**而不是抛异常：调用方（邮件模板/退订链接）不该因为
        //    一个配置缺失就 500；而 verify("", "") 天然 false ⇒ fail-closed 仍然成立。
        if (secret == null || secret.isBlank() || userId == null || userId.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成退订签名", e);
        }
    }

    /** 校验退订签名（常量时间）。secret 为空一律 false（fail-closed）。 */
    public boolean verify(String userId, String token) {
        if (userId == null || userId.isBlank() || token == null || token.isBlank()) return false;
        if (secret == null || secret.isBlank()) return false;
        return MessageDigest.isEqual(
                sign(userId).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 候选人（只在包内可见，测试用）。 */
    public record Candidate(String userId, String email) {
        static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    }
}
