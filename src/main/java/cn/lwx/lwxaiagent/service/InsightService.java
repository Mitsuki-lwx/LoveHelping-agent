package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 对话洞察服务（非诊断，ADR-10 无风险）。
 * 分析用户上传的聊天记录，输出观察性反馈，不做心理诊断。
 */
@Slf4j
@Service
public class InsightService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public InsightService(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 分析聊天记录。
     */
    public Map<String, Object> analyze(String conversation, String sourceType) {
        if (conversation == null || conversation.isBlank()) {
            throw new BizException(400, "聊天记录不能为空");
        }

        // 截断过长输入（保护 token 预算）
        String truncated = conversation.length() > 4000 ? conversation.substring(0, 4000) + "\n\n[后续内容已截断]" : conversation;

        String prompt = """
                你是一个专业的沟通模式分析师。请分析用户提供的聊天记录，返回JSON格式的分析结果：

                {
                  "statistics": {
                    "turns": "对话轮次数量",
                    "userAvgWords": "用户平均每轮字数",
                    "partnerAvgWords": "对方平均每轮字数",
                    "coldStarts": "冷场/中断次数（话题切换或长时间停顿）"
                  },
                  "patterns": [
                    "观察到的事实1",
                    "观察到的事实2"
                  ],
                  "suggestions": [
                    "具体可操作建议1",
                    "具体可操作建议2"
                  ]
                }

                约束：
                - 只描述观察到的语言模式，不做心理诊断
                - 禁止出现：障碍、障碍型、人格、诊断、依恋、焦虑症、抑郁症、人格障碍
                - 禁止预测关系走向
                - 使用"观察到"而不是"诊断出"
                - 建议必须具体可执行，不空泛
                """;

        String fullPrompt = prompt + "\n\n聊天记录：\n" + truncated;

        try {
            var response = chatModel.call(new Prompt(List.of(new UserMessage(fullPrompt))));
            String text = response.getResult().getOutput().getText();

            // 提取 JSON（LLM 可能输出 markdown 包裹）
            String json = text;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```"));
            } else if (json.contains("```")) {
                json = json.substring(json.indexOf("```") + 3, json.lastIndexOf("```"));
            }
            json = json.trim();

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(json, Map.class);

            // 附加免责声明
            result.put("disclaimer", "以上分析基于您提供的对话片段，仅为观察性反馈，不做任何心理或关系诊断。");
            result.put("sourceType", sourceType);

            // 记录用户 ID（用于历史记录）
            result.put("userId", TenantContext.getUserId());

            // 脱敏关键字检查（如果输出中包含诊断性语言，记录下来但返回）
            if (containsDiagnosticTerms(text)) {
                log.warn("Insight output may contain diagnostic language: {}", text.substring(0, Math.min(text.length(), 200)));
                result.put("warning", "分析结果可能包含诊断性语言，请注意甄别。");
            }

            log.info("Insight analysis completed for user {}", TenantContext.getUserId());
            return result;

        } catch (Exception e) {
            log.error("Insight analysis failed: {}", e.getMessage());
            throw new BizException(500, "分析失败，请稍后重试");
        }
    }

    private boolean containsDiagnosticTerms(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("障碍") || lower.contains("人格") || lower.contains("诊断")
                || lower.contains("依恋") || lower.contains("焦虑症") || lower.contains("抑郁症");
    }
}