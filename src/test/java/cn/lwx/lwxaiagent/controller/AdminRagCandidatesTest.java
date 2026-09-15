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
                mock(AuditService.class),mock(RerankDocumentPostProcessor.class));
        when(retriever.retrieve(any())).thenReturn(List.of(new Document("public text",Map.of("filename","public.md")),
                new Document("private text",Map.of("source","memory","filename","private.md"))));
        var req=new MockHttpServletRequest();
        assertFalse(controller.ragRetrieve("query",false,false,req).containsKey("candidates"));
        var result=controller.ragRetrieve("query",false,true,req);
        String candidates=result.get("candidates").toString();
        assertTrue(candidates.contains("public text"));assertFalse(candidates.contains("private text"));
        verify(guard,times(2)).check(req);
    }
    @Test void forbiddenAdminRequestDoesNotRetrieveOrDiscloseCandidates() {
        var guard=mock(AdminGuard.class); var retriever=mock(ParentChildDocumentRetriever.class);
        var controller=new AdminController(mock(GoldenSetRunner.class),guard,mock(CanaryConfig.class),mock(SkillIngestor.class),retriever,
                mock(AuditService.class),mock(RerankDocumentPostProcessor.class));
        doThrow(new BizException(403,"forbidden")).when(guard).check(any());
        assertThrows(BizException.class,()->controller.ragRetrieve("query",false,true,new MockHttpServletRequest()));
        verifyNoInteractions(retriever);
    }
}
