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

    public AdminController(GoldenSetRunner goldenSetRunner, AdminGuard adminGuard,
                           CanaryConfig canaryConfig, SkillIngestor skillIngestor,
                           cn.lwx.lwxaiagent.rag.ParentChildDocumentRetriever documentRetriever) {
        this.goldenSetRunner = goldenSetRunner;
        this.adminGuard = adminGuard;
        this.canaryConfig = canaryConfig;
        this.skillIngestor = skillIngestor;
        this.documentRetriever = documentRetriever;
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
    public Map<String, Object> ragRetrieve(@RequestParam String query, HttpServletRequest request) {
        adminGuard.check(request);
        var docs = documentRetriever.retrieve(new org.springframework.ai.rag.Query(query));
        var files = new java.util.LinkedHashSet<String>();
        for (var d : docs) {
            Object f = d.getMetadata().get("filename");
            if (f != null) {
                files.add(f.toString());
            }
        }
        log.info("Admin rag/retrieve query='{}' hits={} files={}", query.length() > 40 ? query.substring(0, 40) : query,
                docs.size(), files.size());
        return Map.of("query", query, "hits", new java.util.ArrayList<>(files));
    }
}
