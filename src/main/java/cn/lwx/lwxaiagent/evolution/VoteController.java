package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.tenant.TenantContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/evolution")
public class VoteController {

    @Resource
    private KnowledgeVoteMapper voteMapper;

    @PostMapping("/vote")
    public String vote(@RequestBody VoteRequest req) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) tenantId = "default";

        KnowledgeVote existing = voteMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeVote>()
                        .eq(KnowledgeVote::getTenantId, tenantId)
                        .eq(KnowledgeVote::getSessionId, req.sessionId)
                        .eq(KnowledgeVote::getMessageIndex, req.messageIndex));

        if (existing != null) {
            existing.setVoteType(req.voteType);
            existing.setFeedbackText(req.feedbackText);
            voteMapper.updateById(existing);
        } else {
            KnowledgeVote vote = new KnowledgeVote(tenantId, req.sessionId, req.messageIndex, req.voteType);
            vote.setFeedbackText(req.feedbackText);
            voteMapper.insert(vote);
        }

        log.info("Vote: session={}, msg={}, type={}, feedback={}",
                req.sessionId, req.messageIndex, req.voteType, req.feedbackText);
        return "ok";
    }

    public record VoteRequest(String sessionId, Integer messageIndex, String voteType, String feedbackText) {}
}
