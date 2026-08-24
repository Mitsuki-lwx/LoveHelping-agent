package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.constant.FileConstant;
import cn.lwx.lwxaiagent.entity.InsightRecord;
import cn.lwx.lwxaiagent.entity.MessageMedia;
import cn.lwx.lwxaiagent.infrastructure.ai.VisionPort;
import cn.lwx.lwxaiagent.mapper.InsightRecordMapper;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
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
    private final VisionPort visionPort;
    private final MessageMediaMapper mediaMapper;
    private final InsightRecordMapper recordMapper;

    public InsightService(@Qualifier("openAiChatModel") ChatModel chatModel,
                          VisionPort visionPort,
                          MessageMediaMapper mediaMapper,
                          InsightRecordMapper recordMapper) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
        this.visionPort = visionPort;
        this.mediaMapper = mediaMapper;
        this.recordMapper = recordMapper;
    }

    /**
     * 分析聊天记录（粘贴文本 / 截图解析后）。
     */
    public Map<String, Object> analyze(String conversation, String sourceType) {
        if (conversation == null || conversation.isBlank()) {
            throw new BizException(400, "聊天记录不能为空");
        }
        String truncated = conversation.length() > 4000 ? conversation.substring(0, 4000) + "\n\n[后续内容已截断]" : conversation;

        String prompt = """
                你是一个专业的沟通模式分析师。请分析用户提供的聊天记录，返回JSON格式的分析结果：

                {
                  "statistics": {
                    "turns": "对话轮次数量",
                    "userAvgWords": "用户平均每轮字数",
                    "partnerAvgWords": "对方平均每轮字数",
                    "coldStarts": "冷场/中断次数"
                  },
                  "patterns": ["观察到的事实1","观察到的事实2"],
                  "suggestions": ["具体可操作建议1","具体可操作建议2"]
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

            String json = extractJson(text);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(json, Map.class);

            result.put("disclaimer", "以上分析基于您提供的对话片段，仅为观察性反馈，不做任何心理或关系诊断。");
            result.put("sourceType", sourceType);

            // 诊断性语言检测告警
            if (containsDiagnosticTerms(text)) {
                log.warn("Insight output may contain diagnostic language: {}", text.substring(0, Math.min(text.length(), 200)));
                result.put("warning", "分析结果可能包含诊断性语言，请注意甄别。");
            }

            // 保存历史记录（CAP-6）
            saveRecord(conversation, sourceType, result);

            log.info("Insight analysis completed for user {}", TenantContext.getUserId());
            return result;
        } catch (Exception e) {
            log.error("Insight analysis failed: {}", e.getMessage());
            throw new BizException(500, "分析失败，请稍后重试");
        }
    }

    // ==================== 截图解析（CAP-2，Task 3）====================

    /**
     * 从截图解析并分析聊天记录。
     */
    public Map<String, Object> analyzeFromMedia(Long mediaId) {
        String userId = TenantContext.getUserId();
        if (userId == null) throw new BizException(401, "未登录");

        // 加载图片，校验归属
        MessageMedia media = mediaMapper.selectById(mediaId);
        if (media == null || !userId.equals(media.getUserId())) {
            throw new BizException(403, "无权访问该图片");
        }

        // 读取图片字节
        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", media.getObjectKey()));
        } catch (java.io.IOException e) {
            throw new BizException(500, "图片读取失败");
        }

        // VisionPort 提取聊天文字（OCR）
        String mime = "image/jpeg";
        if ("PNG".equals(media.getMediaType())) mime = "image/png";
        else if ("WEBP".equals(media.getMediaType())) mime = "image/webp";

        String extracted = visionPort.chat(
                "请提取这张聊天截图中的所有对话文字，保持角色和内容完整，格式为：用户: xxx\\n对方: xxx",
                List.of(imageBytes), mime);

        // 分析提取的对话
        Map<String, Object> result = analyze(extracted, "SCREENSHOT");
        result.put("ocrNote", "文字由AI识别，可能有误差。");
        return result;
    }

    // ==================== 历史记录（CAP-6，Task 4）====================

    private void saveRecord(String conversation, String sourceType, Map<String, Object> result) {
        try {
            String userId = TenantContext.getUserId();
            if (userId == null) return;

            InsightRecord record = new InsightRecord();
            record.setUserId(userId);
            record.setSourceType(sourceType);
            // 摘要：取统计信息的前N条
            Object stats = result.get("statistics");
            record.setSummary(stats != null ? stats.toString() : conversation.substring(0, Math.min(conversation.length(), 200)));
            record.setCreatedAt(LocalDateTime.now());
            recordMapper.insert(record);
        } catch (Exception e) {
            log.warn("Failed to save insight record: {}", e.getMessage());
        }
    }

    /** 列出用户历史分析记录。 */
    public List<InsightRecord> listRecords(String userId) {
        return recordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InsightRecord>()
                        .eq(InsightRecord::getUserId, userId)
                        .orderByDesc(InsightRecord::getCreatedAt)
                        .last("LIMIT 50"));
    }

    /** 获取单条记录（归属校验）。 */
    public InsightRecord getRecord(Long id, String userId) {
        InsightRecord record = recordMapper.selectById(id);
        if (record == null) throw new BizException(404, "记录不存在");
        if (!record.getUserId().equals(userId)) throw new BizException(403, "无权访问");
        return record;
    }

    /** 删除记录（CAP-6 隐私）。 */
    public void deleteRecord(Long id, String userId) {
        InsightRecord record = getRecord(id, userId);
        recordMapper.deleteById(id);
    }

    // ==================== 工具 ====================

    private String extractJson(String text) {
        if (text.contains("```json")) {
            return text.substring(text.indexOf("```json") + 7, text.lastIndexOf("```")).trim();
        } else if (text.contains("```")) {
            return text.substring(text.indexOf("```") + 3, text.lastIndexOf("```")).trim();
        }
        return text.trim();
    }

    private boolean containsDiagnosticTerms(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("障碍") || lower.contains("人格") || lower.contains("诊断")
                || lower.contains("依恋") || lower.contains("焦虑症") || lower.contains("抑郁症");
    }
}
