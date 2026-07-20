package cn.lwx.lwxaiagent.evolution;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Async;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ConversationExtractor {

    @Resource
    private ChatMemoryRepository chatMemoryRepository;

    @Resource
    private KnowledgeVoteMapper voteMapper;

    @Resource
    private KnowledgeIngestor knowledgeIngestor;

    @Resource
    private KnowledgeEntryMapper entryMapper;

    private final ChatClient chatClient;
    private final int qualityThreshold;

    public ConversationExtractor(ChatModel chatModel, int qualityThreshold) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.qualityThreshold = qualityThreshold;
    }

    @Async("evolutionExecutor")
    public void extractSession(String chatId, String tenantId) {
        try {
            if (entryMapper.countBySessionId(chatId) > 0) {
                log.debug("Session {} already extracted, skipping", chatId);
                return;
            }

            List<Message> messages = chatMemoryRepository.findByConversationId(chatId);
            List<KnowledgeVote> votes = voteMapper.findBySessionId(chatId);

            List<ExtractedEntry> entries = doExtract(formatConversation(messages), formatVotes(votes));

            List<ExtractedEntry> valid = new ArrayList<>();
            for (ExtractedEntry e : entries) {
                if (e.qualityScore() != null && e.qualityScore() >= qualityThreshold) {
                    valid.add(e);
                }
            }

            if (!valid.isEmpty()) {
                knowledgeIngestor.ingest(valid, tenantId, chatId);
                log.info("Extracted {} valid entries from session {}", valid.size(), chatId);
            } else {
                log.info("No valid entries extracted from session {}", chatId);
            }

        } catch (Exception e) {
            log.error("Failed to extract session {}: {}", chatId, e.getMessage(), e);
        }
    }

    private List<ExtractedEntry> doExtract(String conversation, String votes) {
        ExtractionResult result = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("""
                        对话内容：
                        %s
                        
                        用户反馈：
                        %s
                        """.formatted(conversation, votes))
                .call()
                .entity(ExtractionResult.class);

        if (result == null || result.entries() == null) {
            return List.of();
        }
        return result.entries();
    }

    private String formatConversation(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.getMessageType() == MessageType.SYSTEM || msg.getMessageType() == MessageType.TOOL) {
                continue;
            }
            String role = msg.getMessageType() == MessageType.USER ? "用户" : "AI";
            sb.append("[").append(role).append("] ").append(msg.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private String formatVotes(List<KnowledgeVote> votes) {
        if (votes == null || votes.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (KnowledgeVote vote : votes) {
            String typeStr = switch (vote.getVoteType()) {
                case "LIKE" -> "👍 认可";
                case "DISLIKE" -> "👎 不认可";
                default -> "中立";
            };
            sb.append("第 ").append(vote.getMessageIndex()).append(" 条AI回复: ").append(typeStr);
            if (vote.getFeedbackText() != null && !vote.getFeedbackText().isBlank()) {
                sb.append(" (用户反馈: ").append(vote.getFeedbackText()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public record ExtractionResult(List<ExtractedEntry> entries) {}
    public record ExtractedEntry(String entryType, String label, String title,
                                 String content, String tags, Integer qualityScore) {}

    private static final String SYSTEM_PROMPT = """
            你是一个知识萃取系统。从恋爱咨询对话中提取有价值的经验。
            
            返回 JSON 数组，每个元素包含：
            - entryType: "PROCESS" | "PATTERN" | "PRINCIPLE" | "CASE"
            - label: "GOOD" | "BAD"
            - title: 简短标题（10-20字）
            - content: 详细内容（50-200字，完整可复用）
            - tags: 逗号分隔的关键词
            - qualityScore: 1-10 的整数
            
            类型说明：
            - PROCESS: 可重复的流程或步骤
            - PATTERN: 可识别的模式或规律
            - PRINCIPLE: 核心原则或价值观
            - CASE: 具体案例（含上下文、做法、结果）
            
            评分标准（qualityScore）：
            - 10-8: 完整且普适的经验，可直接复用
            - 7-5: 有一定参考价值，需适当调整
            - 4-1: 内容空洞、模糊、无实质帮助
            
            规则：
            - 纯寒暄/无实质内容的对话不提取
            - 用户点 👍 的回复对应条目加分
            - 用户点 👎 的回复作为反面案例（label=BAD）
            - 只返回 JSON，不要额外文字
            """;
}
