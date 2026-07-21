package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.KnowledgeVote;
import cn.lwx.lwxaiagent.mapper.KnowledgeVoteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class EvolutionService {

    private final KnowledgeVoteMapper voteMapper;

    public EvolutionService(KnowledgeVoteMapper voteMapper) {
        this.voteMapper = voteMapper;
    }

    @Transactional
    public void vote(String tenantId, VoteRequest req) {
        if (tenantId == null) tenantId = "default";
        String finalTenantId = tenantId;

        KnowledgeVote existing = voteMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeVote>()
                        .eq(KnowledgeVote::getTenantId, finalTenantId)
                        .eq(KnowledgeVote::getSessionId, req.sessionId)
                        .eq(KnowledgeVote::getMessageIndex, req.messageIndex));

        if (existing != null) {
            existing.setVoteType(req.voteType);
            existing.setFeedbackText(req.feedbackText);
            voteMapper.updateById(existing);
        } else {
            KnowledgeVote vote = new KnowledgeVote(finalTenantId, req.sessionId, req.messageIndex, req.voteType);
            vote.setFeedbackText(req.feedbackText);
            voteMapper.insert(vote);
        }

        log.info("Vote: session={}, msg={}, type={}, feedback={}",
                req.sessionId, req.messageIndex, req.voteType, req.feedbackText);
    }

    public record VoteRequest(
            @NotBlank(message = "sessionId 不能为空") String sessionId,
            @NotNull(message = "messageIndex 不能为空") Integer messageIndex,
            @NotBlank(message = "voteType 不能为空") @Pattern(regexp = "LIKE|DISLIKE|NEUTRAL", message = "voteType 必须为 LIKE/DISLIKE/NEUTRAL") String voteType,
            String feedbackText) {}
}
