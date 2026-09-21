package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切块器。
 * <p>
 * <b>实现是 overlap 扁平切块</b>（句子级滑动窗口）：累积到 {@code TARGET=400} 字符收块，
 * 下一块起点回溯约 {@code OVERLAP=80} 字，metadata 只打 {@code chunk=overlap} + {@code chunk_index}，
 * <b>没有 {@code parent_id}/{@code parent_text}</b>，也没有父块聚合或 small-to-large 回填。
 * </p>
 * <p>
 * ⚠️ <b>名字是遗留的</b>：2026-09-05 由"父子索引"改为 overlap 扁平（当时两轮对照评测：
 * 69 文档库 overlap MRR 0.906 vs 父子 0.817；扩 3 篇长文后 0.848 vs 0.842），
 * 类名、本注释与入库日志里的 {@code "Parent-child split"} 文案都**没有跟着改**，
 * 曾导致"记录与实现矛盾"的误判（2026-09-21 逐行核实：以代码为准，就是 overlap 扁平）。
 * </p>
 */
public class ParentChildDocumentTransformer implements DocumentTransformer {

    /** 目标块长（字符）：2026-09-05 由父子索引改为 overlap 扁平切块——两轮对照评测
     * （69 文档库 overlap MRR 0.906 vs 父子 0.817；扩 3 篇长文后 0.848 vs 0.842）
     * overlap 从未输过且无父子链复杂度、注入更省上下文 → 父子为过度设计。 */
    private static final int TARGET = 400;
    /** 相邻块共享尾部长度（字符）：交界语义两边都完整，防止切点截断 */
    private static final int OVERLAP = 80;

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        for (Document doc : documents) {
            splitOverlap(doc, chunks);
        }
        return chunks;
    }

    /**
     * 句子级滑动窗口切块：句子为最小单位（保语义），累积到 TARGET 收块，
     * 下一块起点回溯到距本块尾约 OVERLAP 的句子（交界语义在相邻块都完整）。
     */
    private void splitOverlap(Document doc, List<Document> out) {
        List<String> sentences = splitSentences(doc.getText());
        if (sentences.isEmpty()) {
            return;
        }
        int start = 0;
        int idx = 0;
        while (start < sentences.size()) {
            StringBuilder buf = new StringBuilder();
            int end = start;
            while (end < sentences.size() && (buf.length() < TARGET || end == start)) {
                buf.append(sentences.get(end));
                end++;
            }
            String chunkText = buf.toString().trim();
            if (!chunkText.isEmpty()) {
                Document d = new Document(chunkText, new java.util.HashMap<>(doc.getMetadata()));
                d.getMetadata().put("chunk", "overlap");
                d.getMetadata().put("chunk_index", idx++);
                out.add(d);
            }
            if (end >= sentences.size()) {
                break;
            }
            // 下一窗口起点：从 end 回溯，让重叠部分约 OVERLAP 字（至少前进一句防死循环）
            int next = end;
            int tail = 0;
            while (next > start + 1 && tail < OVERLAP) {
                next--;
                tail += sentences.get(next).length();
            }
            start = next;
        }
    }

    /** 按中英文句末标点切句（保留标点），容忍无标点长段 */
    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            buf.append(ch);
            if ("。！？…；!?".indexOf(ch) >= 0) {
                String s = buf.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                buf.setLength(0);
            }
        }
        if (!buf.toString().trim().isEmpty()) {
            out.add(buf.toString().trim());
        }
        return out;
    }
}
