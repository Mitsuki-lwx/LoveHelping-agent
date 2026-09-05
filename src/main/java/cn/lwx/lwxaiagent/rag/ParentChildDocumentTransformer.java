package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子索引切分器（ADR-15，P2-B）。
 * <p>
 * 解决"整篇文档一个 chunk"的问题：先按段落聚合出父块（~1500 字符，上下文完整），
 * 父块内再按句子切出子块（~400 字符，语义聚焦、检索精确）。
 * 子块 embedding 入库，metadata 携带 {@code parent_id} 与 {@code parent_text}——
 * 检索命中子块后可直接返回父块全文注入 prompt（small-to-large 模式）。
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
