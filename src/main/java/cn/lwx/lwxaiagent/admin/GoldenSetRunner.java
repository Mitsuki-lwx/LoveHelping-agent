package cn.lwx.lwxaiagent.admin;

import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Golden Set 评估器：用标准用例集回归测试 Prompt 质量。
 * 每条用例发送到聊天管道 → LLM-as-judge 按 rubric 打分 + 禁用词校验。
 * 门禁：≥80% 通过，60-80% 人工复审，<60% 阻断。
 */
@Slf4j
@Service
public class GoldenSetRunner {

    private final ChatExecutor chatExecutor;
    private final ChatModel judgeModel; // LLM-as-judge（独立模型）

    public GoldenSetRunner(ChatExecutor chatExecutor,
                           @org.springframework.beans.factory.annotation.Qualifier("deepSeekChatModel") ChatModel judgeModel) {
        this.chatExecutor = chatExecutor;
        this.judgeModel = judgeModel;
    }

    /**
     * 运行 Golden Set 评估。
     * @return 评估报告（通过率、每条结果）
     */
    public GoldenReport run() {
        List<GoldenCase> cases = loadCases();
        List<CaseResult> results = new ArrayList<>();
        int pass = 0;

        for (GoldenCase c : cases) {
            try {
                // 1. 调用聊天管道
                String reply = callChat(c.input());

                // 2. 禁用词检测
                boolean bannedHit = false;
                for (String word : c.bannedWords()) {
                    if (reply.toLowerCase().contains(word.toLowerCase())) {
                        bannedHit = true;
                        break;
                    }
                }

                // 3. LLM-as-judge
                double score = judge(c.input(), reply, c.rubric());

                boolean passed = score >= 6.0 && !bannedHit;
                if (passed) pass++;

                results.add(new CaseResult(c.id(), c.input(), reply.substring(0, Math.min(reply.length(), 100)),
                        score, bannedHit, passed));
            } catch (Exception e) {
                log.warn("Golden case {} failed: {}", c.id(), e.getMessage());
                results.add(new CaseResult(c.id(), c.input(), "ERROR: " + e.getMessage(), 0, false, false));
            }
        }

        double rate = (double) pass / cases.size();
        String verdict = rate >= 0.8 ? "PASS" : rate >= 0.6 ? "REVIEW" : "FAIL";
        GoldenReport report = new GoldenReport(pass, cases.size(), rate, verdict, results);
        log.info("Golden Set: {}/{} passed ({:.0f}%), verdict={}", pass, cases.size(), rate * 100, verdict);
        return report;
    }

    private String callChat(String input) {
        cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult result = chatExecutor.execute(input, "golden_test_" + UUID.randomUUID(), cn.lwx.lwxaiagent.infrastructure.orchestration.CapabilitySet.plain());
        if (result instanceof cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult.ShallowResult sr) {
            List<String> chunks = sr.flux().collectList().block();
            if (chunks != null && !chunks.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                chunks.forEach(sb::append);
                return sb.toString();
            }
        }
        return "";
    }

    private double judge(String input, String reply, String rubric) {
        try {
            String prompt = "你是一个严格的对话质量评审员。请评估以下AI回复的质量。\n\n用户问题: " + input + "\n\nAI回复: " + reply + "\n\n评估维度: " + rubric + "\n\n请从1-10分评分，只输出数字分数。";
            var response = judgeModel.call(new org.springframework.ai.chat.prompt.Prompt(List.of(new UserMessage(prompt))));
            String text = response.getResult().getOutput().getText();
            double score = 5.0; // default
            try {
                score = Double.parseDouble(text.trim().replaceAll("[^0-9.]", ""));
            } catch (Exception ignored) {}
            return Math.min(10, Math.max(0, score));
        } catch (Exception e) {
            log.warn("Judge failed: {}", e.getMessage());
            return 5.0;
        }
    }

    private List<GoldenCase> loadCases() {
        try {
            String json = new String(new ClassPathResource("golden-set.json").getInputStream().readAllBytes());
            return Arrays.asList(new ObjectMapper().readValue(json, GoldenCase[].class));
        } catch (Exception e) {
            log.error("Failed to load golden-set.json: {}", e.getMessage());
            return List.of();
        }
    }

    public record GoldenCase(int id, String input, String rubric, List<String> bannedWords) {}
    public record CaseResult(int id, String input, String reply, double score, boolean bannedHit, boolean passed) {}
    public record GoldenReport(int passed, int total, double rate, String verdict, List<CaseResult> results) {}
}
