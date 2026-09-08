package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.entity.SentimentLog;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmGateway;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import cn.lwx.lwxaiagent.mapper.SentimentLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 情绪时间线（V22，2026-09-08 产品闭环 ④）：按会话粒度打情绪分（-2 ~ +2），
 * 让用户在旧信存档里看见"这两周其实在变好"。
 *
 * <p>懒计算：timeline 请求时对最近未打分的会话补算（每次最多 5 个，避免请求被
 * LLM 调用拖死）；打分材料=会话前几条消息，一次 LLM 调用输出 [SCORE]/[WHY]。
 * 解析失败按 0 分中性记录（不阻塞时间线）。
 */
@Slf4j
@Service
public class SentimentService {

    private static final Pattern SCORE_P = Pattern.compile("\\[SCORE\\]\\s*(-?[0-2])\\s*\\[/SCORE\\]");
    private static final Pattern WHY_P = Pattern.compile("\\[WHY\\](.*?)\\[/WHY\\]", Pattern.DOTALL);

    private final SentimentLogMapper sentimentMapper;
    private final MessageMapper messageMapper;
    private final EncryptionService encryptionService;
    private final LlmGateway llmGateway;

    public SentimentService(SentimentLogMapper sentimentMapper, MessageMapper messageMapper,
                            EncryptionService encryptionService, LlmGateway llmGateway) {
        this.sentimentMapper = sentimentMapper;
        this.messageMapper = messageMapper;
        this.encryptionService = encryptionService;
        this.llmGateway = llmGateway;
    }

    /**
     * 情绪时间线：已打分会话按时间正序返回；对最近的未打分非沙盘会话懒补算（≤5）。
     */
    public List<SentimentLog> timeline(String userId) {
        backfillLatest(userId);
        return sentimentMapper.selectList(new LambdaQueryWrapper<SentimentLog>()
                .eq(SentimentLog::getUserId, userId)
                .orderByAsc(SentimentLog::getCreatedAt)
                .last("LIMIT 60"));
    }

    /** 对最近未打分的会话补算（排除沙盘会话——其 chatId 为纯数字） */
    private void backfillLatest(String userId) {
        try {
            Set<String> scored = new java.util.HashSet<>();
            for (SentimentLog s : sentimentMapper.selectList(new LambdaQueryWrapper<SentimentLog>()
                    .eq(SentimentLog::getUserId, userId).select(SentimentLog::getChatId))) {
                scored.add(s.getChatId());
            }
            // 用户最近的会话列表（按最新消息时间倒序，取 12 个候选）
            List<Message> latest = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getUserId, userId)
                    .eq(Message::getRole, "USER")
                    .eq(Message::getDeleted, 0)
                    .orderByDesc(Message::getId)
                    .last("LIMIT 60"));
            List<String> chatIds = new ArrayList<>();
            for (Message m : latest) {
                String cid = m.getConversationId();
                if (cid == null || scored.contains(cid) || chatIds.contains(cid)) continue;
                if (cid.chars().allMatch(Character::isDigit)) continue; // 沙盘会话不进情绪线
                chatIds.add(cid);
                if (chatIds.size() >= 5) break;
            }
            for (String cid : chatIds) {
                scoreConversation(userId, cid);
            }
        } catch (Exception e) {
            log.warn("sentiment backfill skipped: {}", e.getMessage());
        }
    }

    /**
     * 按上报文本打分（V1 主路径）：love-chat 对话历史存前端 localStorage，
     * message 表无真实用户记录——前端在回信完成后上报本轮用户消息打分。
     * 已打分（同 chatId）跳过；失败静默。
     */
    public void scoreText(String userId, String chatId, String userText) {
        try {
            if (userId == null || chatId == null || userText == null || userText.isBlank()) return;
            Long dup = sentimentMapper.selectCount(new LambdaQueryWrapper<SentimentLog>()
                    .eq(SentimentLog::getUserId, userId).eq(SentimentLog::getChatId, chatId));
            if (dup != null && dup > 0) return;

            String system = """
                    你是情绪记录助手。根据用户写来的这段话，给用户当下的情绪状态打分。
                    评分范围 -2 到 2：-2=非常糟糕/危机，-1=低落，0=平静，1=有起色，2=明显变好/积极。
                    请严格按以下格式输出：
                    [SCORE]（-2 到 2 的整数）[/SCORE]
                    [WHY]（一句中文，20 字内）[/WHY]""";
            ChatResponse resp = llmGateway.call(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage("用户的话：" + abbreviate(userText, 300))
            )));
            String raw = resp != null && resp.getResult() != null && resp.getResult().getOutput() != null
                    ? resp.getResult().getOutput().getText() : null;
            int score = 0;
            String why = null;
            if (raw != null) {
                Matcher sm = SCORE_P.matcher(raw);
                if (sm.find()) score = Integer.parseInt(sm.group(1));
                Matcher wm = WHY_P.matcher(raw);
                if (wm.find() && !wm.group(1).isBlank()) why = wm.group(1).trim();
            }
            SentimentLog log1 = new SentimentLog();
            log1.setUserId(userId);
            log1.setChatId(chatId);
            log1.setScore(score);
            log1.setReason(why);
            sentimentMapper.insert(log1);
        } catch (Exception e) {
            log.warn("sentiment scoreText failed: {}", e.getMessage());
        }
    }

    /** 单会话打分（已存在则跳过；失败静默——情绪线是增强不是硬依赖） */
    public void scoreConversation(String userId, String chatId) {
        try {
            Long dup = sentimentMapper.selectCount(new LambdaQueryWrapper<SentimentLog>()
                    .eq(SentimentLog::getUserId, userId).eq(SentimentLog::getChatId, chatId));
            if (dup != null && dup > 0) return;

            List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, chatId)
                    .eq(Message::getDeleted, 0)
                    .orderByAsc(Message::getId)
                    .last("LIMIT 6"));
            if (rows.isEmpty()) return;

            StringBuilder transcript = new StringBuilder();
            for (Message r : rows) {
                String content = safeDecrypt(r.getContent(), r.getUserId());
                String who = "USER".equals(r.getRole()) ? "用户" : "AI";
                transcript.append(who).append("：").append(abbreviate(content, 200)).append("\n");
            }

            String system = """
                    你是情绪记录助手。根据下面这段对话（开头部分），给用户在这场对话里的情绪状态打分。
                    评分范围 -2 到 2：-2=非常糟糕/危机，-1=低落，0=平静，1=有起色，2=明显变好/积极。
                    只评用户的情绪走向，不评 AI。请严格按以下格式输出：
                    [SCORE]（-2 到 2 的整数）[/SCORE]
                    [WHY]（一句中文，20 字内，说明为什么）[/WHY]""";

            ChatResponse resp = llmGateway.call(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage("对话：\n" + transcript)
            )));
            String raw = resp != null && resp.getResult() != null && resp.getResult().getOutput() != null
                    ? resp.getResult().getOutput().getText() : null;

            int score = 0;
            String why = null;
            if (raw != null) {
                Matcher sm = SCORE_P.matcher(raw);
                if (sm.find()) score = Integer.parseInt(sm.group(1));
                Matcher wm = WHY_P.matcher(raw);
                if (wm.find() && !wm.group(1).isBlank()) why = wm.group(1).trim();
            }

            SentimentLog log1 = new SentimentLog();
            log1.setUserId(userId);
            log1.setChatId(chatId);
            log1.setScore(score);
            log1.setReason(why);
            sentimentMapper.insert(log1);
        } catch (Exception e) {
            log.warn("sentiment score failed chatId={}: {}", chatId, e.getMessage());
        }
    }

    private String safeDecrypt(String content, String userId) {
        try { return encryptionService.decrypt(content, userId); }
        catch (Exception e) { return content; }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
