package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.entity.GuardrailEvent;
import cn.lwx.lwxaiagent.mapper.GuardrailEventMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 护栏审计写入器（ADR-6）：<b>只存 content_hmac，不存原文</b>（07 §6）。
 *
 * <p>抽出来是因为有两个写入点：{@link GuardrailAdvisor}（模型调用前后）与
 * {@code ChatEntry.guardrailCheck}（输入拦截，含影子观测）。
 * 两处各写一份 {@code sha256} 的话，日后一旦有一处改了算法，
 * "同一句话"在两个来源就对不上号——审计路径上这种漂移很难被发现。统一到一处。</p>
 */
@Slf4j
@Component
public class GuardrailEventRecorder {

    /** 词典/模型判定为需阻断。 */
    public static final String ACTION_BLOCKED = "BLOCKED";
    /** 命中但仅记录（L1/L2）。 */
    public static final String ACTION_LOGGED = "LOGGED";
    /** 影子观测：第二信号判定完成，但<b>不改变响应</b>（V24）。 */
    public static final String ACTION_SHADOW = "SHADOW";

    private final GuardrailEventMapper mapper;

    public GuardrailEventRecorder(GuardrailEventMapper mapper) {
        this.mapper = mapper;
    }

    public void record(String userText, int level, String ruleId, String action) {
        record(userText, level, ruleId, action, null, null);
    }

    /**
     * @param userText        用户原文（<b>只取其哈希</b>，不落库）
     * @param signalScore     第二信号概率，无则传 {@code null}（既有路径不受影响）
     * @param signalThreshold 判定时所用阈值，无则传 {@code null}
     */
    public void record(String userText, int level, String ruleId, String action,
                       Double signalScore, Double signalThreshold) {
        try {
            GuardrailEvent event = new GuardrailEvent();
            event.setUserId(TenantContext.getUserId());
            event.setLevel(level);
            event.setRuleId(ruleId);
            event.setContentHmac(sha256(userText));
            event.setAction(action);
            if (signalScore != null) event.setSignalScore(BigDecimal.valueOf(signalScore));
            if (signalThreshold != null) event.setSignalThreshold(BigDecimal.valueOf(signalThreshold));
            event.setCreatedAt(LocalDateTime.now());
            mapper.insert(event);
        } catch (Exception e) {
            // 审计失败不得影响主流程（护栏已做出决策，这里只是记账）
            log.warn("Failed to record guardrail event: {}", e.getMessage());
        }
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
