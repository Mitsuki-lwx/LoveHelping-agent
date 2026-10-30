package cn.lwx.lwxaiagent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库增量同步的**陈旧清理**（ADR-89）。
 *
 * <p>⛔ 此前只处理"新增/变更/未变"，<b>源文件删掉后残留向量永久留在库里</b> ——
 * docs/09 里"删文档必须手动清 target/classes/document/ 再 reindex"就是它的手工兜底。</p>
 *
 * <p>这组测试的重点不是"能删"，而是<b>不该删的时候一条都不删</b>：
 * 清空知识库、误删用户记忆都是不可逆事故。</p>
 */
@DisplayName("KnowledgeBaseIncrementalSync：陈旧文档清理（ADR-89）")
class KnowledgeBaseIncrementalSyncTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ParentChildDocumentTransformer transformer = new ParentChildDocumentTransformer();
    private final KnowledgeBaseIncrementalSync sync = new KnowledgeBaseIncrementalSync();

    private static Document doc(String filename) {
        return new Document("内容-" + filename, Map.of("filename", filename, "doc_hash", "h-" + filename));
    }

    /** 库里有 A/B/C，当前只剩 A/B ⇒ C 必须被清掉。 */
    @Test
    void staleFileIsPurged() {
        when(jdbc.queryForList(contains("DISTINCT metadata->>'filename'"), eq(String.class)))
                .thenReturn(List.of("a.md", "b.md", "c.md"));
        // 按文件名分别桩：a/b 的 hash 与库里一致 ⇒ 跳过（**不许被删**）
        when(jdbc.queryForList(contains("doc_hash"), eq(String.class), eq("a.md"))).thenReturn(List.of("h-a.md"));
        when(jdbc.queryForList(contains("doc_hash"), eq(String.class), eq("b.md"))).thenReturn(List.of("h-b.md"));
        // DELETE 的"影响行数"要桩出来：Mockito 默认返回 0，否则 deleted() 恒为 0（假绿/假红都来自这）
        when(jdbc.update(contains("DELETE FROM vector_store"), anyString())).thenReturn(1);

        KnowledgeBaseIncrementalSync.SyncResult r =
                sync.sync(vectorStore, jdbc, transformer, List.of(doc("a.md"), doc("b.md")));

        verify(jdbc).update(contains("DELETE FROM vector_store"), eq("c.md"));
        assertThat(r.deleted()).isEqualTo(1);
        // 仍在集合里的绝不能被删
        verify(jdbc, never()).update(anyString(), eq("a.md"));
        verify(jdbc, never()).update(anyString(), eq("b.md"));
    }

    /**
     * ⛔ 一份文档都没加载到（例如运营目录配错、classpath 被裁）⇒ **一条都不许删**。
     * 宁可有陈旧数据，也不能把知识库清空。
     */
    @Test
    void emptyDocumentSetDeletesNothing() {
        when(jdbc.queryForList(contains("DISTINCT metadata->>'filename'"), eq(String.class)))
                .thenReturn(List.of("a.md", "b.md"));

        KnowledgeBaseIncrementalSync.SyncResult r =
                sync.sync(vectorStore, jdbc, transformer, List.of());

        verify(jdbc, never()).update(contains("DELETE FROM vector_store"), anyString());
        assertThat(r.deleted()).isZero();
    }

    /**
     * 删除的作用域必须带 `filename IS NOT NULL` 守卫 —— 用户记忆与进化技能不带 filename，
     * 一旦去掉这个守卫就会误删用户数据（已实测：库里 1130 行，记忆 691 行全部无 filename）。
     */
    @Test
    void purgeQueryIsScopedToRowsWithFilename() {
        when(jdbc.queryForList(contains("DISTINCT metadata->>'filename'"), eq(String.class)))
                .thenReturn(List.of());

        sync.sync(vectorStore, jdbc, transformer, List.of(doc("a.md")));

        verify(jdbc).queryForList(contains("metadata->>'filename' IS NOT NULL"), eq(String.class));
    }

    /** 无 filename 的文档不参与比对，也不该让清理逻辑误判（记忆/技能就是这种形态）。 */
    @Test
    void documentsWithoutFilenameAreIgnored() {
        when(jdbc.queryForList(contains("DISTINCT metadata->>'filename'"), eq(String.class)))
                .thenReturn(List.of("keep.md"));
        Document noFile = new Document("记忆内容", Map.of("source", "memory"));

        KnowledgeBaseIncrementalSync.SyncResult r =
                sync.sync(vectorStore, jdbc, transformer, List.of(doc("keep.md"), noFile));

        verify(jdbc, never()).update(contains("DELETE FROM vector_store"), anyString());
        assertThat(r.deleted()).isZero();
    }
}
