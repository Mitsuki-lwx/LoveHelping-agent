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

    /** 父块目标长度（字符） */
    private static final int PARENT_TARGET = 1500;
    /** 子块目标长度（字符） */
    private static final int CHILD_TARGET = 400;

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> children = new ArrayList<>();
        for (Document doc : documents) {
            splitParent(doc, children);
        }
        return children;
    }

    /**
     * 单个文档 → 多个父块 → 每个父块切子块。
     */
    private void splitParent(Document doc, List<Document> out) {
        List<String> parents = splitByParagraphs(doc.getText(), PARENT_TARGET);
        for (int p = 0; p < parents.size(); p++) {
            String parentText = parents.get(p);
            String parentId = (String) doc.getMetadata().getOrDefault("source", "doc") + "#p" + p;
            List<String> childTexts = splitBySentences(parentText, CHILD_TARGET);
            for (int c = 0; c < childTexts.size(); c++) {
                Document child = new Document(childTexts.get(c), doc.getMetadata());
                child.getMetadata().put("parent_id", parentId);
                child.getMetadata().put("parent_text", parentText);
                child.getMetadata().put("chunk", "child");
                out.add(child);
            }
        }
    }

    /**
     * 按空行段落聚合到目标长度。
     */
    private List<String> splitByParagraphs(String text, int target) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder buf = new StringBuilder();
        for (String p : paragraphs) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (buf.length() + s.length() > target && buf.length() > 0) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(s).append("\n");
        }
        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }
        return result;
    }

    /**
     * 按句子边界（。！？…）切到目标长度；无边界时按字符硬切。
     */
    private List<String> splitBySentences(String text, int target) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            buf.append(ch);
            boolean isBoundary = "。！？…\n".indexOf(ch) >= 0;
            if ((isBoundary || buf.length() >= target) && buf.length() >= 40) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }
        return result;
    }
}
