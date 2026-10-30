package cn.lwx.lwxaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库增量同步（ADR-15 阶段 4 · content_hash）：
 * <p>以文档级 hash（metadata {@code doc_hash}，文件 {@code filename} 为标识）对比库中存量，
 * 启动时只重建<b>新增或变更</b>的文档——文档更新不再需要全量 reindex，堵住"索引静默过时"。
 * 旧格式块（无 doc_hash）首轮按"变更"整体重建一次，之后增量生效。</p>
 */
@Slf4j
@Component
public class KnowledgeBaseIncrementalSync {

    public record SyncResult(int added, int replaced, int skipped, int deleted) {
    }

    /** 对当前文档集执行增量同步；返回 新增/重建/跳过 计数 */
    public SyncResult sync(VectorStore vectorStore, JdbcTemplate pgJdbcTemplate,
                           ParentChildDocumentTransformer transformer, List<Document> documents) {
        int added = 0, replaced = 0, skipped = 0;
        for (Document doc : documents) {
            String file = (String) doc.getMetadata().get("filename");
            String hash = (String) doc.getMetadata().get("doc_hash");
            if (file == null || hash == null) {
                continue;
            }
            String existing = queryHash(pgJdbcTemplate, file);
            if (existing == null) {
                insertDoc(vectorStore, transformer, doc);
                added++;
            } else if (hash.equals(existing)) {
                skipped++;
            } else {
                try {
                    pgJdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'filename' = ?", file);
                } catch (Exception e) {
                    log.warn("Incremental sync delete failed for {}: {}", file, e.getMessage());
                }
                insertDoc(vectorStore, transformer, doc);
                replaced++;
            }
        }
        // ⛔ 2026-10-13（ADR-89）补：**源文件被删除时清理残留向量**。
        //    此前只处理"新增/变更/未变"三种情形，删掉的文档向量会永久留在库里 ——
        //    docs/09 里那句"删文档必须手动清 target/classes/document/ 再 reindex"
        //    就是这个缺口的手工兜底（靠人记住，必然会忘）。
        int deleted = purgeStale(pgJdbcTemplate, documents);
        log.info("KnowledgeBase incremental sync: added={} replaced={} skipped={} deleted={}",
                added, replaced, skipped, deleted);
        return new SyncResult(added, replaced, skipped, deleted);
    }

    /**
     * 清掉"库里还有、当前文档集里已经没有"的知识文档（按 {@code filename} 比对）。
     *
     * <p>⛔ <b>作用域是安全的</b>：只删 {@code metadata->>'filename' IS NOT NULL} 的行。
     * 用户记忆（{@code source=memory}）与进化技能（{@code source=evolution}）都<b>不带 filename</b>
     * —— 已实测确认（2026-10-13：库里 1130 行，带 filename 的 439 行全部是知识库，
     * 691 行记忆一个都没有）。所以这个删除**碰不到**用户数据。</p>
     */
    private int purgeStale(JdbcTemplate pgJdbcTemplate, List<Document> documents) {
        java.util.Set<String> current = new java.util.HashSet<>();
        for (Document d : documents) {
            Object f = d.getMetadata().get("filename");
            if (f != null) current.add(String.valueOf(f));
        }
        if (current.isEmpty()) {
            // 一份文档都没加载到（如运营目录配错）→ **不删**。宁可有陈旧数据，也不能清空知识库。
            log.warn("本次未加载到任何知识文档，跳过陈旧清理（避免误清空）");
            return 0;
        }
        List<String> dbFiles;
        try {
            dbFiles = pgJdbcTemplate.queryForList(
                    "SELECT DISTINCT metadata->>'filename' FROM vector_store "
                            + "WHERE metadata->>'filename' IS NOT NULL", String.class);
        } catch (Exception e) {
            log.warn("陈旧文档比对失败，跳过清理：{}", e.getMessage());
            return 0;
        }
        int deleted = 0;
        for (String f : dbFiles) {
            if (current.contains(f)) continue;
            try {
                deleted += pgJdbcTemplate.update(
                        "DELETE FROM vector_store WHERE metadata->>'filename' = ?", f);
                log.info("陈旧知识文档已清理：{}", f);
            } catch (Exception e) {
                log.warn("清理陈旧文档失败 {}：{}", f, e.getMessage());
            }
        }
        return deleted;
    }

    private String queryHash(JdbcTemplate pgJdbcTemplate, String file) {
        try {
            List<String> hashes = pgJdbcTemplate.queryForList(
                    "SELECT metadata->>'doc_hash' FROM vector_store WHERE metadata->>'filename' = ? LIMIT 1",
                    String.class, file);
            return hashes.isEmpty() ? null : hashes.get(0);
        } catch (Exception e) {
            log.warn("Incremental sync hash query failed for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void insertDoc(VectorStore vectorStore, ParentChildDocumentTransformer transformer, Document doc) {
        try {
            List<Document> children = transformer.apply(List.of(doc));
            vectorStore.add(children);
        } catch (Exception e) {
            log.warn("Incremental sync insert failed for {}: {}", doc.getMetadata().get("filename"), e.getMessage());
        }
    }
}