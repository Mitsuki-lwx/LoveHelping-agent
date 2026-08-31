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

    public record SyncResult(int added, int replaced, int skipped) {
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
        log.info("KnowledgeBase incremental sync: added={} replaced={} skipped={}", added, replaced, skipped);
        return new SyncResult(added, replaced, skipped);
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