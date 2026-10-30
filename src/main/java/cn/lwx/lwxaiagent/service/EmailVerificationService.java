package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.mail.MailSenderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;

/**
 * <h1>邮箱验证码服务（V33）—— 只存 Redis，不落库</h1>
 *
 * <h2>存储</h2>
 * <ul>
 *   <li>验证码 key：{@code auth:code:{purpose}:{target}}，<b>TTL 5 分钟</b>；</li>
 *   <li>存的是 <b>SHA-256 哈希</b>而不是明文——即使 Redis 被 dump / 运维直接 GET，
 *       也拿不到可直接使用的验证码；</li>
 *   <li>不写数据库：验证码是短命凭证，落库只会留下无意义的敏感历史。</li>
 * </ul>
 *
 * <h2>校验</h2>
 * <ul>
 *   <li><b>一次性</b>：校验通过即删除（连错误计数一起删）；</li>
 *   <li><b>错 5 次即作废</b>：错误次数记在 {@code auth:code:try:{purpose}:{target}}，
 *       达到 5 次直接删掉验证码，之后即使输入正确也必须重新获取；</li>
 *   <li><b>文案统一</b>：「不存在 / 已过期 / 不匹配」对外一律同一句
 *       {@link #INVALID_MESSAGE}——否则「验证码不存在」与「验证码错误」的差异本身
 *       就是账号是否存在的侧信道。</li>
 * </ul>
 *
 * <h2>限流</h2>
 * <ul>
 *   <li>同一 target <b>60 秒内只能发一次</b>（{@code setIfAbsent} 占位）；</li>
 *   <li>同一 target <b>每小时最多 5 次</b>（{@code auth:code:quota:...} 计数 + 1 小时 TTL）。</li>
 * </ul>
 *
 * @see cn.lwx.lwxaiagent.mail.MailSenderPort 邮件端口（SMTP / 开发日志降级）
 */
@Slf4j
@Service
public class EmailVerificationService {

    /** 验证码用途：绑定邮箱（target = 待绑定邮箱）。 */
    public static final String PURPOSE_BIND = "bind";
    /** 验证码用途：重置密码（target = 用户名）。 */
    public static final String PURPOSE_RESET = "reset";

    /** 校验失败（不存在/过期/不匹配）的统一文案——防账号枚举侧信道。 */
    public static final String INVALID_MESSAGE = "验证码不正确或已失效，请重新获取";
    /** 错误次数用尽的文案。 */
    public static final String TOO_MANY_ATTEMPTS_MESSAGE = "验证码错误次数过多，请重新获取";

    /** 验证码有效期。 */
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** 同一 target 的发送冷却时间。 */
    static final Duration COOLDOWN = Duration.ofSeconds(60);
    /** 同一 target 的限流窗口。 */
    static final Duration RATE_WINDOW = Duration.ofHours(1);
    /** 限流窗口内最多发送次数。 */
    static final int MAX_PER_WINDOW = 5;
    /** 允许的错误尝试次数（达到即作废）。 */
    static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final MailSenderPort mail;

    public EmailVerificationService(StringRedisTemplate redis, MailSenderPort mail) {
        this.redis = redis;
        this.mail = mail;
    }

    /**
     * 限流（不发送）：同一 target 60 秒一次、每小时最多 5 次，超限抛 429。
     */
    public void enforceRateLimit(String purpose, String target) {
        String cooldownKey = cooldownKey(purpose, target);
        Boolean acquired = redis.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(429, "验证码发送过于频繁，请 60 秒后再试");
        }
        String quotaKey = quotaKey(purpose, target);
        Long used = redis.opsForValue().increment(quotaKey);
        if (used != null && used == 1L) {
            redis.expire(quotaKey, RATE_WINDOW);
        }
        if (used != null && used > MAX_PER_WINDOW) {
            redis.delete(cooldownKey);   // 归还冷却位，避免"超限"与"冷却"双重惩罚
            throw new BizException(429, "验证码发送次数过多，请 1 小时后再试");
        }
    }

    /**
     * 生成一次性验证码：<b>限流 → 存哈希（TTL 5 分钟）→ 发信</b>。
     *
     * <p>{@code email} 为空/空白时<b>仍然写入验证码</b>但不发信——这样「账号不存在/未绑定邮箱」
     * 与「已绑定」在存储与后续校验（含错误次数）上的行为完全一致，不留枚举侧信道。
     * 调用方（{@code EmailRecoveryService}）据此实现"无论账号是否存在都返回成功"。</p>
     *
     * @param purpose {@link #PURPOSE_BIND} / {@link #PURPOSE_RESET}
     * @param target  验证码归属（绑定=邮箱；重置=用户名）
     * @param email   实际收件地址；空 = 只写码不发信（防枚举路径）
     */
    public void issue(String purpose, String target, String email) {
        enforceRateLimit(purpose, target);
        String code = newCode();
        redis.opsForValue().set(codeKey(purpose, target), sha256(code), CODE_TTL);
        redis.delete(tryKey(purpose, target));   // 重新发码 = 错误次数清零
        if (email == null || email.isBlank()) {
            log.info("验证码已生成但未发送（目标未绑定邮箱）：purpose={}, target={}", purpose, target);
            return;
        }
        mail.send(email, subjectOf(purpose), bodyOf(purpose, code));
    }

    /**
     * 校验验证码：通过则<b>一次性删除</b>；失败累计错误次数，达到 5 次直接作废。
     *
     * @throws BizException 400：{@link #INVALID_MESSAGE} 或 {@link #TOO_MANY_ATTEMPTS_MESSAGE}
     */
    public void verify(String purpose, String target, String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(400, INVALID_MESSAGE);
        }
        String key = codeKey(purpose, target);
        String stored = redis.opsForValue().get(key);
        if (stored == null) {
            throw new BizException(400, INVALID_MESSAGE);   // 不存在/已过期/已用过：与"错误"同文案
        }
        String tryKey = tryKey(purpose, target);
        Long attempts = redis.opsForValue().increment(tryKey);
        if (attempts != null && attempts == 1L) {
            redis.expire(tryKey, CODE_TTL);   // 错误计数与验证码同寿命
        }
        if (!stored.equals(sha256(code))) {
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                redis.delete(key);
                redis.delete(tryKey);
                log.warn("验证码错误次数超限，已作废：purpose={}, target={}", purpose, target);
                throw new BizException(400, TOO_MANY_ATTEMPTS_MESSAGE);
            }
            throw new BizException(400, INVALID_MESSAGE);
        }
        redis.delete(key);       // 一次性：校验通过即失效
        redis.delete(tryKey);
    }

    /** 6 位数字验证码（SecureRandom，避免可预测序列）。 */
    private static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** SHA-256 十六进制（存哈希，不存明文）。 */
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);   // JDK 必然支持
        }
    }

    private static String codeKey(String purpose, String target) {
        return "auth:code:" + purpose + ":" + target;
    }

    private static String tryKey(String purpose, String target) {
        return "auth:code:try:" + purpose + ":" + target;
    }

    private static String cooldownKey(String purpose, String target) {
        return "auth:code:cooldown:" + purpose + ":" + target;
    }

    private static String quotaKey(String purpose, String target) {
        return "auth:code:quota:" + purpose + ":" + target;
    }

    private static String subjectOf(String purpose) {
        return PURPOSE_RESET.equals(purpose) ? "【恋恋】重置密码验证码" : "【恋恋】绑定邮箱验证码";
    }

    private static String bodyOf(String purpose, String code) {
        String action = PURPOSE_RESET.equals(purpose) ? "重置登录密码" : "绑定邮箱";
        return "您正在进行「" + action + "」操作，验证码：" + code + "\n"
                + "验证码 5 分钟内有效，且只能使用一次。\n"
                + "如果这不是您本人的操作，请忽略本邮件，您的账号仍然安全。";
    }
}
