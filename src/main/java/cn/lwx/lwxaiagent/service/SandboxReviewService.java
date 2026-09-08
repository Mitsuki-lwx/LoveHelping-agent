package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.entity.SandboxSession;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmGateway;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 沙盘演练复盘（2026-09-08 产品闭环 ③）：练完给点评——
 * 哪句话升级了对抗、哪次修复尝试被接住、换成哪句会更好。
 *
 * <p>约束：走 {@link LlmGateway}（降级链）；消息读取解密与 {@code MessageChatMemory} 同规则
 * （decrypt 失败按明文兜底）；输出按 [SUMMARY]/[GOOD]/[RISK]/[BETTER] 解析，段缺失返回 null。
 */
@Slf4j
@Service
public class SandboxReviewService {

    private static final Pattern SUMMARY_P = Pattern.compile("\\[SUMMARY\\](.*?)\\[/SUMMARY\\]", Pattern.DOTALL);
    private static final Pattern GOOD_P = Pattern.compile("\\[GOOD\\](.*?)\\[/GOOD\\]", Pattern.DOTALL);
    private static final Pattern RISK_P = Pattern.compile("\\[RISK\\](.*?)\\[/RISK\\]", Pattern.DOTALL);
    private static final Pattern BETTER_P = Pattern.compile("\\[BETTER\\](.*?)\\[/BETTER\\]", Pattern.DOTALL);

    private final SandboxService sandboxService;
    private final MessageMapper messageMapper;
    private final EncryptionService encryptionService;
    private final LlmGateway llmGateway;

    public SandboxReviewService(SandboxService sandboxService, MessageMapper messageMapper,
                                EncryptionService encryptionService, LlmGateway llmGateway) {
        this.sandboxService = sandboxService;
        this.messageMapper = messageMapper;
        this.encryptionService = encryptionService;
        this.llmGateway = llmGateway;
    }

    public record ReviewResult(String summary, String good, String risk, String better) {}

    public ReviewResult review(Long sessionId, String userId) {
        SandboxSession session = sandboxService.getSession(sessionId, userId);

        // 读取演练记录（与 MessageChatMemory 同规则：解密失败按明文兜底）
        List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, String.valueOf(sessionId))
                .eq(Message::getDeleted, 0)
                .orderByAsc(Message::getId)
                .last("LIMIT 40"));
        StringBuilder transcript = new StringBuilder();
        int turns = 0;
        for (Message r : rows) {
            String content = safeDecrypt(r.getContent(), r.getUserId());
            String who = "USER".equals(r.getRole()) ? "用户" : "TA";
            transcript.append(who).append("：").append(abbreviate(content, 300)).append("\n");
            if ("USER".equals(r.getRole())) turns++;
        }
        if (turns < 2) {
            throw new BizException(400, "这次演练聊得太短了，先和 TA 多聊几句再来复盘");
        }

        String system = """
                你是一位旁观的关系沟通教练。用户刚和 TA（AI 扮演的角色）进行了一场沟通演练。
                请基于下面的对话记录，从旁观者视角给出复盘。要求具体、引用原话、不空泛；
                语气温和但直接，不奉承。禁止输出操控、羞辱或危险内容。
                请严格按以下格式输出，不要额外解释：
                [SUMMARY]（两句话总结这场演练的整体走向）[/SUMMARY]
                [GOOD]（用户说得好的地方，引用原话，1-2 句；若没有写"这场里暂时没有明显的好时刻"）[/GOOD]
                [RISK]（哪句话可能升级对抗或传递了错误信号，引用原话，1-2 句；若没有写"没有明显风险点"）[/RISK]
                [BETTER]（给出一句更好的说法，可直接照着用）[/BETTER]""";

        ChatResponse resp = llmGateway.call(new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage("对话记录：\n" + transcript)
        )));
        String raw = resp != null && resp.getResult() != null && resp.getResult().getOutput() != null
                ? resp.getResult().getOutput().getText() : null;
        if (raw == null || raw.isBlank()) {
            throw new BizException(500, "复盘生成失败，稍后再试");
        }
        ReviewResult result = new ReviewResult(
                extract(raw, SUMMARY_P), extract(raw, GOOD_P), extract(raw, RISK_P), extract(raw, BETTER_P));
        // 宽容降级：全部解析失败时整段作 summary（剥掉残留标签）
        if (result.summary() == null && result.good() == null) {
            result = new ReviewResult(
                    raw.replaceAll("\\[/?(SUMMARY|GOOD|RISK|BETTER)\\]", "").trim(), null, null, null);
        }
        // summary 段内残留标签同样剥掉
        String sm2 = result.summary();
        if (sm2 != null && sm2.contains("[SUMMARY]")) {
            sm2 = sm2.replaceAll("\\[/?(SUMMARY|GOOD|RISK|BETTER)\\]", "").trim();
            result = new ReviewResult(sm2, result.good(), result.risk(), result.better());
        }
        return result;
    }

    private String safeDecrypt(String content, String userId) {
        try { return encryptionService.decrypt(content, userId); }
        catch (Exception e) { return content; }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String extract(String raw, Pattern p) {
        if (raw == null) return null;
        Matcher m = p.matcher(raw);
        if (!m.find()) return null;
        String s = m.group(1).trim();
        return s.isEmpty() ? null : s;
    }
}
