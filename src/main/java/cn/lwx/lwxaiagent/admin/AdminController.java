package cn.lwx.lwxaiagent.admin;

import cn.lwx.lwxaiagent.canary.CanaryConfig;
import cn.lwx.lwxaiagent.evolution.SkillIngestor;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final GoldenSetRunner goldenSetRunner;
    private final AdminGuard adminGuard;
    private final CanaryConfig canaryConfig;
    private final SkillIngestor skillIngestor;
    /** 检索器（评测/排查用——绕过 classify 与日志，直接看检索层命中） */
    private final org.springframework.ai.rag.retrieval.search.DocumentRetriever documentRetriever;
    /** 审计查询（V20 课3）：敏感操作事后取证 */
    private final cn.lwx.lwxaiagent.service.AuditService auditService;
    /** 生产链路的 postretrieval 重排（ADR-25）：评测端点复用它，使检索评测能覆盖 rerank */
    private final cn.lwx.lwxaiagent.rag.rerank.RerankDocumentPostProcessor rerankPostProcessor;
    /** 生产链路的查询改写（ADR-15）：评测端点复用它，使检索评测能覆盖 rewrite（2026-09-21） */
    private final cn.lwx.lwxaiagent.rag.QueryRewriter queryRewriter;
    public AdminController(GoldenSetRunner goldenSetRunner, AdminGuard adminGuard,
                           CanaryConfig canaryConfig, SkillIngestor skillIngestor,
                           cn.lwx.lwxaiagent.rag.ParentChildDocumentRetriever documentRetriever,
                           cn.lwx.lwxaiagent.service.AuditService auditService,
                           cn.lwx.lwxaiagent.rag.rerank.RerankDocumentPostProcessor rerankPostProcessor,
                           cn.lwx.lwxaiagent.rag.QueryRewriter queryRewriter) {
        this.goldenSetRunner = goldenSetRunner;
        this.adminGuard = adminGuard;
        this.canaryConfig = canaryConfig;
        this.skillIngestor = skillIngestor;
        this.documentRetriever = documentRetriever;
        this.auditService = auditService;
        this.rerankPostProcessor = rerankPostProcessor;
        this.queryRewriter = queryRewriter;
    }

    /** 审计日志查询（X-Admin-Key 保护）：?limit=50 最近 N 条敏感操作记录 */
    @GetMapping("/audit")
    public java.util.Map<String, Object> audit(HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        adminGuard.check(request);
        var entries = auditService.recent(limit);
        return java.util.Map.of("total", entries.size(), "entries", entries);
    }

    @PostMapping("/golden-set/run")
    public Map<String, Object> runGoldenSet(HttpServletRequest request) {
        adminGuard.check(request);
        log.info("Golden Set evaluation triggered by admin");
        var report = goldenSetRunner.run();
        return Map.of(
                "passed", report.passed(),
                "total", report.total(),
                "rate", String.format("%.0f%%", report.rate() * 100),
                "verdict", report.verdict(),
                "details", report.results()
        );
    }

    @GetMapping("/canary/status")
    public Map<String, Object> canaryStatus(HttpServletRequest request) {
        adminGuard.check(request);
        String userId = TenantContext.getUserId();
        int bucket = userId != null ? Math.abs(userId.hashCode() % 100) : -1;
        return Map.of(
                "percentage", canaryConfig.getPercentage(),
                "currentUserId", userId != null ? userId : "anonymous",
                "currentBucket", bucket,
                "isCanary", canaryConfig.isCanary(userId)
        );
    }

    /**
     * 批量向量化所有已审核的进化技能（admin 权限）。
     * 用于补齐已审核但未向量化的技能（如直接在 DB 审核的场景）。
     */
    @PostMapping("/evolution/skills/vectorize")
    public Map<String, Object> vectorizeApprovedSkills(HttpServletRequest request) {
        adminGuard.check(request);
        log.info("Batch vectorization triggered by admin");
        int count = skillIngestor.vectorizeAllApproved();
        return Map.of("success", true, "vectorized", count);
    }

    /**
     * 检索层调试/评测端点（admin）：直接跑 retriever（不经 classify/聊天链路），
     * 返回命中的父文档 filename 列表——检索评测从"日志嗅探"升级为显式 API。
     */
    @GetMapping("/rag/retrieve")
    public Map<String, Object> ragRetrieve(@RequestParam String query,
            @RequestParam(defaultValue = "false") boolean rerank,
            @RequestParam(defaultValue = "false") boolean rewrite,
            @RequestParam(defaultValue = "false") boolean includeCandidates, HttpServletRequest request) {
        adminGuard.check(request);
        if (query == null || query.isBlank() || query.length() > 8000) throw new cn.lwx.lwxaiagent.common.BizException(400, "查询长度无效");
        // 可选查询改写（2026-09-21 新增，与已有的 rerank 参数同一动机）：
        // 改写此前挂在 RetrievalAugmentationAdvisor 的 pre-retrieval 里，评测端点绕不过去 →
        // "改写好还是坏"只能靠聊天链路的日志嗅探，量不准也复现不了。这里复用产线 QueryRewriter，
        // 让同一套 ground truth 能把 不改写/v1/v2 并排跑出来。
        String effectiveQuery = query;
        if (rewrite) {
            effectiveQuery = queryRewriter.transform(new org.springframework.ai.rag.Query(query)).text();
        }
        var ragQuery = new org.springframework.ai.rag.Query(effectiveQuery);
        var docs = documentRetriever.retrieve(ragQuery);
        // 可选重排：复用生产 postretrieval 组件，使检索评测可量化 rerank（ADR-25）
        if (rerank) {
            docs = rerankPostProcessor.process(ragQuery, docs);
        }
        var files = new java.util.LinkedHashSet<String>();
        for (var d : docs) {
            Object f = d.getMetadata().get("filename");
            if (f != null) {
                files.add(f.toString());
            }
        }
        log.info("Admin rag/retrieve queryChars={} rerank={} rewrite={} hits={} files={}", query.length(),
                rerank, rewrite, docs.size(), files.size());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("query", query); result.put("rerank", rerank); result.put("rewrite", rewrite);
        result.put("effectiveQuery", effectiveQuery);
        result.put("promptVersion", queryRewriter.promptVersionName());
        result.put("hits", new java.util.ArrayList<>(files));
        if (includeCandidates) {
            // Admin-only bounded snapshot of the ACTUAL input to rerank. Never return memory/skill data.
            result.put("candidates", docs.stream().filter(d -> !java.util.Set.of("memory", "evolution")
                    .contains(java.util.Objects.toString(d.getMetadata().get("source"), ""))).limit(50)
                    .map(d -> Map.of("id", d.getId(), "filename", java.util.Objects.toString(d.getMetadata().get("filename"), ""),
                            "text", d.getText() == null ? "" : d.getText().substring(0, Math.min(1200, d.getText().length())))).toList());
        }
        return result;
    }

}
