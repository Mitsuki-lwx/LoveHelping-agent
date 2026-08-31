package cn.lwx.lwxaiagent.rag.rerank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 重排 v1（ADR-15 阶段 4）：用主线模型（LlmGateway，@Primary）对候选打分排序。
 * <p>把 query + 编号候选（每候选截断控 token）发给模型，要求只返回按相关性排序的前 K 个编号；
 * 解析编号保序映射回候选；非法/缺失编号用原顺序补位（<b>不丢文档</b>）；
 * 模型/解析失败降级为原顺序前 K（不中断 RAG）。</p>
 */
@Slf4j
@Component
public class LlmDocumentReranker implements DocumentReranker {

    private final ChatModel chatModel;
    private final RerankProperties properties;

    private static final Pattern NUMBER_LINE = Pattern.compile("(?m)^\\s*(\\d{1,3})\\s*[.、)）]?\\s*(?:\\s*[-:：]\\s*.*)?$");

    public LlmDocumentReranker(ChatModel chatModel, RerankProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        // 退化：候选不够，不调模型直接返回
        if (candidates.size() <= topK) {
            return candidates;
        }
        List<String> excerpt = new ArrayList<>();
        for (Document d : candidates) {
            excerpt.add(truncate(d.getText(), properties.getMaxCandidateChars()));
        }
        String prompt = buildPrompt(query, excerpt, topK);
        String raw;
        try {
            raw = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("LLM rerank call failed, fallback to original order: {}", e.getMessage());
            return new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
        }
        List<Integer> chosen = parseIndices(raw, candidates.size());
        List<Document> result = applyOrder(candidates, chosen, topK);
        if (result.size() < topK) {
            // 模型给的编号太少/非法：用原顺序补足（不丢文档）
            Set<Integer> used = new HashSet<>(chosen.stream().map(i -> i).toList());
            int i = 0;
            while (result.size() < topK && i < candidates.size()) {
                if (!used.contains(i)) {
                    result.add(candidates.get(i));
                }
                i++;
            }
        }
        return result;
    }

    private String buildPrompt(String query, List<String> excerpt, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是检索重排器。根据查询与候选文档，选出最相关的 ").append(topK)
                .append(" 个，按相关性从高到低排列。\n");
        sb.append("只输出编号，每行一个，不要输出任何解释、序号符号或多余文字。\n\n");
        sb.append("查询：").append(query).append("\n\n候选：\n");
        for (int i = 0; i < excerpt.size(); i++) {
            sb.append(i + 1).append(": ").append(excerpt.get(i)).append("\n");
        }
        sb.append("\n相关度最高的 ").append(topK).append(" 个编号（每行一个）：");
        return sb.toString();
    }

    /** 解析模型输出中的编号（按出现顺序），越界编号丢弃 */
    private List<Integer> parseIndices(String raw, int candidateCount) {
        List<Integer> idx = new ArrayList<>();
        if (raw == null) return idx;
        Matcher m = NUMBER_LINE.matcher(raw);
        Set<Integer> seen = new HashSet<>();
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1).trim());
                int zero = v - 1;
                if (zero >= 0 && zero < candidateCount && seen.add(zero)) {
                    idx.add(zero);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return idx;
    }

    /** 保序映射：编号 → 候选；不足 topK 由调用方补位 */
    private List<Document> applyOrder(List<Document> candidates, List<Integer> chosen, int topK) {
        List<Document> out = new ArrayList<>();
        for (int i : chosen) {
            if (out.size() >= topK) break;
            out.add(candidates.get(i));
        }
        return out;
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }
}