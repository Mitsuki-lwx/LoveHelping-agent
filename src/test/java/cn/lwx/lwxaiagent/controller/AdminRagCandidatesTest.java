package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.admin.*;
import cn.lwx.lwxaiagent.canary.CanaryConfig;
import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.evolution.SkillIngestor;
import cn.lwx.lwxaiagent.rag.ParentChildDocumentRetriever;
import cn.lwx.lwxaiagent.rag.rerank.RerankDocumentPostProcessor;
import cn.lwx.lwxaiagent.service.AuditService;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminRagCandidatesTest {
    @Test void optInCandidatesAreBoundedAndNeverExposeMemoryDocuments() {
        var guard=mock(AdminGuard.class); var retriever=mock(ParentChildDocumentRetriever.class);
        var controller=new AdminController(mock(GoldenSetRunner.class),guard,mock(CanaryConfig.class),mock(SkillIngestor.class),retriever,
                mock(AuditService.class),mock(RerankDocumentPostProcessor.class),mock(cn.lwx.lwxaiagent.rag.QueryRewriter.class));
        when(retriever.retrieve(any())).thenReturn(List.of(new Document("public text",Map.of("filename","public.md")),
                new Document("private text",Map.of("source","memory","filename","private.md"))));
        var req=new MockHttpServletRequest();
        assertFalse(controller.ragRetrieve("query",false,false,false,req).containsKey("candidates"));
        var result=controller.ragRetrieve("query",false,false,true,req);
        String candidates=result.get("candidates").toString();
        assertTrue(candidates.contains("public text"));assertFalse(candidates.contains("private text"));
        verify(guard,times(2)).check(req);
    }
    /** rewrite=false 时必须用**原查询**检索（不能悄悄改写）。 */
    @Test void rewriteOffRetrievesWithOriginalQuery() {
        var retriever=mock(ParentChildDocumentRetriever.class);
        var rewriter=mock(cn.lwx.lwxaiagent.rag.QueryRewriter.class);
        var controller=new AdminController(mock(GoldenSetRunner.class),mock(AdminGuard.class),mock(CanaryConfig.class),
                mock(SkillIngestor.class),retriever,mock(AuditService.class),mock(RerankDocumentPostProcessor.class),rewriter);
        when(retriever.retrieve(any())).thenReturn(List.of(new Document("t",Map.of("filename","a.md"))));
        var r=controller.ragRetrieve("冷战筑墙怎么办",false,false,false,new MockHttpServletRequest());
        verify(rewriter,never()).transform(any());
        var used=org.mockito.ArgumentCaptor.forClass(org.springframework.ai.rag.Query.class);
        verify(retriever).retrieve(used.capture());
        assertEquals("冷战筑墙怎么办",used.getValue().text());
        assertEquals("冷战筑墙怎么办",r.get("effectiveQuery"));
    }

    /** rewrite=true 时必须用**产线改写器**的产出检索，并把实际 query 与提示词版本回显（可归因）。 */
    @Test void rewriteOnRetrievesWithRewrittenQueryAndEchoesVersion() {
        var retriever=mock(ParentChildDocumentRetriever.class);
        var rewriter=mock(cn.lwx.lwxaiagent.rag.QueryRewriter.class);
        var controller=new AdminController(mock(GoldenSetRunner.class),mock(AdminGuard.class),mock(CanaryConfig.class),
                mock(SkillIngestor.class),retriever,mock(AuditService.class),mock(RerankDocumentPostProcessor.class),rewriter);
        when(rewriter.transform(any())).thenReturn(new org.springframework.ai.rag.Query("冷战筑墙怎么办 冷暴力 筑墙 应对"));
        when(rewriter.promptVersionName()).thenReturn("V2");
        when(retriever.retrieve(any())).thenReturn(List.of(new Document("t",Map.of("filename","a.md"))));
        var r=controller.ragRetrieve("冷战筑墙怎么办",false,true,false,new MockHttpServletRequest());
        var used=org.mockito.ArgumentCaptor.forClass(org.springframework.ai.rag.Query.class);
        verify(retriever).retrieve(used.capture());
        assertEquals("冷战筑墙怎么办 冷暴力 筑墙 应对",used.getValue().text());
        assertEquals("冷战筑墙怎么办 冷暴力 筑墙 应对",r.get("effectiveQuery"));
        assertEquals("V2",r.get("promptVersion"));
        // 原查询必须被完整保留（约束版提示词的核心性质，在端点层再守一道）
        assertTrue(used.getValue().text().startsWith("冷战筑墙怎么办"));
    }

    @Test void forbiddenAdminRequestDoesNotRetrieveOrDiscloseCandidates() {
        var guard=mock(AdminGuard.class); var retriever=mock(ParentChildDocumentRetriever.class);
        var controller=new AdminController(mock(GoldenSetRunner.class),guard,mock(CanaryConfig.class),mock(SkillIngestor.class),retriever,
                mock(AuditService.class),mock(RerankDocumentPostProcessor.class),mock(cn.lwx.lwxaiagent.rag.QueryRewriter.class));
        doThrow(new BizException(403,"forbidden")).when(guard).check(any());
        assertThrows(BizException.class,()->controller.ragRetrieve("query",false,false,true,new MockHttpServletRequest()));
        verifyNoInteractions(retriever);
    }
}
