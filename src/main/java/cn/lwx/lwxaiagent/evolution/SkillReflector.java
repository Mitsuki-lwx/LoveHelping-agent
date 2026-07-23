package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.entity.KnowledgeVote;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import cn.lwx.lwxaiagent.mapper.KnowledgeVoteMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 技能反思器 —— 替代 ConversationExtractor。
 * <p>
 * 在会话空闲超时后触发，读取完整对话 + 用户赞踩数据，
 * 调用 LLM 提炼可复用的技能经验，存入 MySQL 和向量库。
 */
@Slf4j
public class SkillReflector {

    @Resource
    private ChatMemoryRepository chatMemoryRepository;

    @Resource
    private KnowledgeVoteMapper voteMapper;

    @Resource
    private EvolutionSkillMapper skillMapper;

    @Resource
    private SkillIngestor skillIngestor;

    private final ChatClient chatClient;
    private final int qualityThreshold;

    public SkillReflector(ChatModel chatModel, int qualityThreshold) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.qualityThreshold = qualityThreshold;
    }

    @Async("evolutionExecutor")
    @Transactional
    public void reflect(String chatId, String tenantId) {
        try {
            if (skillMapper.countBySessionId(chatId) > 0) {
                log.debug("Session {} already reflected, skipping", chatId);
                return;
            }

            List<Message> messages = chatMemoryRepository.findByConversationId(chatId);
            if (messages.isEmpty()) {
                log.debug("Session {} has no messages, skipping reflection", chatId);
                return;
            }

            List<KnowledgeVote> votes = voteMapper.findBySessionId(chatId);
            String conversation = formatConversation(messages);
            String voteFeedback = formatVotes(votes);

            List<SkillReflectionResult> skills = doReflect(conversation, voteFeedback);

            List<SkillReflectionResult> valid = skills.stream()
                    .filter(s -> s.qualityScore() != null && s.qualityScore() >= qualityThreshold)
                    .toList();

            if (!valid.isEmpty()) {
                skillIngestor.ingest(valid, tenantId, chatId);
                log.info("Reflected {} valid skills from session {} (votes: {})",
                        valid.size(), chatId, votes.size());
            } else {
                log.info("No valid skills reflected from session {}", chatId);
            }

        } catch (Exception e) {
            log.error("Failed to reflect session {}: {}", chatId, e.getMessage(), e);
        }
    }

    private List<SkillReflectionResult> doReflect(String conversation, String votes) {
        SkillReflectionOutput result = chatClient.prompt()
                .system(REFLECTION_SYSTEM_PROMPT)
                .user("""
                        Conversation:
                        %s

                        User feedback:
                        %s
                        """.formatted(conversation, votes))
                .call()
                .entity(SkillReflectionOutput.class);

        if (result == null || result.skills() == null) {
            return List.of();
        }
        return result.skills();
    }

    private String formatConversation(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getMessageType() == MessageType.SYSTEM || msg.getMessageType() == MessageType.TOOL) {
                continue;
            }
            String role = msg.getMessageType() == MessageType.USER ? "User" : "AI";
            sb.append("[").append(role).append("] ").append(msg.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private String formatVotes(List<KnowledgeVote> votes) {
        if (votes == null || votes.isEmpty()) return "No user feedback";
        StringBuilder sb = new StringBuilder();
        for (KnowledgeVote vote : votes) {
            String typeStr = switch (vote.getVoteType()) {
                case "LIKE" -> "👍 User liked";
                case "DISLIKE" -> "👎 User disliked";
                default -> "➖ Neutral";
            };
            sb.append("Reply #").append(vote.getMessageIndex()).append(": ").append(typeStr);
            if (vote.getFeedbackText() != null && !vote.getFeedbackText().isBlank()) {
                sb.append(" (feedback: ").append(vote.getFeedbackText()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** LLM 返回的 JSON 结构 */
    public record SkillReflectionOutput(List<SkillReflectionResult> skills) {}

    /** 单条反思结果 */
    public record SkillReflectionResult(
            String skillName,
            String description,
            String content,
            Integer qualityScore) {}

    private static final String REFLECTION_SYSTEM_PROMPT = """
            You are a love counseling experience extraction system. Analyze the following
            conversation and user feedback to extract reusable practical experience.
            Return a JSON array.

            Each skill entry must contain:
            - skillName: Short skill name (max 10 words, e.g. "Empathize Before Advising")
            - description: When to use this skill (20-50 words, used for semantic search matching,
              e.g. "When user vents about partner conflicts with strong emotions")
            - content: Concrete actionable guidance (50-200 words, directly injectable as AI prompt.
              Must be specific and operational, avoid vague platitudes)
            - qualityScore: integer 1-10

            Scoring criteria:
            - 10-8: Complete and widely applicable, directly reusable
            - 7-5: Has reference value, needs contextual adaptation
            - 4-1: Vague, hollow, no practical help (do not extract)

            User likes (👍) → corresponding approach is worth learning, extract as positive experience
            User dislikes (👎) → corresponding approach should be avoided, extract as negative lesson

            Rules:
            - Skip small talk or conversations with no substantive content
            - Content must be specific and actionable; avoid generic advice like "be more caring"
            - Negative lessons should use "Avoid..." or "Do not..." in content
            - Return ONLY JSON in format: {"skills": [...]}
            - Return {"skills": []} if nothing worth extracting
            """;
}
