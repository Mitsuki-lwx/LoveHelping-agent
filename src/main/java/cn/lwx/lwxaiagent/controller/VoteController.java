package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.audit.AuditLog;
import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import cn.lwx.lwxaiagent.evolution.SkillIngestor;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import cn.lwx.lwxaiagent.service.EvolutionService;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import cn.lwx.lwxaiagent.vo.SkillVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/evolution")
public class VoteController {

    /** 合法审核状态。 */
    private static final Set<String> AUDIT_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");
    /** 审核列表单页上限（防 12k+ 条一次拉爆）。 */
    static final int SKILL_LIST_MAX_LIMIT = 200;
    /** 批量驳回单次处理上限。 */
    static final int BULK_REJECT_MAX_LIMIT = 5000;
    /** 批量驳回默认条数。 */
    static final int BULK_REJECT_DEFAULT_LIMIT = 2000;
    /** 批量 UPDATE 的批大小（≤500：避免大事务长时间锁表）。 */
    static final int BULK_BATCH_SIZE = 500;

    private final EvolutionService evolutionService;
    private final EvolutionSkillMapper skillMapper;
    private final SkillIngestor skillIngestor;
    private final AdminGuard adminGuard;

    public VoteController(EvolutionService evolutionService,
                          EvolutionSkillMapper skillMapper,
                          SkillIngestor skillIngestor,
                          AdminGuard adminGuard) {
        this.evolutionService = evolutionService;
        this.skillMapper = skillMapper;
        this.skillIngestor = skillIngestor;
        this.adminGuard = adminGuard;
    }

    @PostMapping("/vote")
    public Result<String> vote(@Valid @RequestBody EvolutionService.VoteRequest req) {
        evolutionService.vote(TenantContext.getTenantId(), req);
        return Result.ok("ok");
    }

    /**
     * 审核进化技能（PENDING → APPROVED/REJECTED）。
     * APPROVED 时自动向量化入库，技能才可被检索注入。
     */
    @PostMapping("/skill/{id}/audit")
    public Result<String> auditSkill(@PathVariable Long id,
                                     @RequestParam String status,
                                     HttpServletRequest request) {
        adminGuard.check(request);
        EvolutionSkill skill = skillMapper.selectById(id);
        if (skill == null) {
            throw new BizException(404, "技能不存在");
        }
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new BizException(400, "status 只能为 APPROVED 或 REJECTED");
        }
        skill.setAuditStatus(status);
        if ("APPROVED".equals(status)) {
            skill.setIsActive(true);
            skillMapper.updateById(skill);
            // 向量化（只对 APPROVED 的做）
            skillIngestor.vectorize(skill);
            log.info("Skill {} APPROVED and vectorized", id);
        } else {
            skill.setIsActive(false);
            skillMapper.updateById(skill);
            log.info("Skill {} REJECTED", id);
        }
        return Result.ok("ok");
    }

    /**
     * 技能审核列表（V34 审核闭环）——补上「列表」这一环。
     *
     * <p>⛔ 必须带分页/limit：库里 PENDING 曾有 12,532 条，无条件全量查询会拖垮内存与响应。
     * status 缺省 PENDING；limit 缺省 50、上限 {@value #SKILL_LIST_MAX_LIMIT}。</p>
     */
    @GetMapping("/skills")
    public Result<List<SkillVO>> listSkills(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            HttpServletRequest request) {
        adminGuard.check(request);
        String st = (status == null || status.isBlank()) ? "PENDING" : status;
        if (!AUDIT_STATUSES.contains(st)) {
            throw new BizException(400, "status 只能为 PENDING/APPROVED/REJECTED");
        }
        int n = (limit == null || limit < 1) ? 50 : Math.min(limit, SKILL_LIST_MAX_LIMIT);
        List<SkillVO> list = skillMapper.findByAuditStatus(st, n).stream().map(VoteController::toVo).toList();
        return Result.ok(list);
    }

    /**
     * 某状态的**真实**候选条数（V35）。
     *
     * <p>给管理端显示"你看到的是前 N 条、积压共 M 条"。⛔ 前端不得写死这个数字：
     * 写死就会与真实积压漂移，变成界面在撒谎（实测踩过：写死 12,532，实际 8,347）。</p>
     */
    @GetMapping("/skills/count")
    public Result<Long> countSkills(
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            HttpServletRequest request) {
        adminGuard.check(request);
        String st = (status == null || status.isBlank()) ? "PENDING" : status;
        if (!AUDIT_STATUSES.contains(st)) {
            throw new BizException(400, "status 只能为 PENDING/APPROVED/REJECTED");
        }
        return Result.ok(skillMapper.countByAuditStatus(st));
    }

    /**
     * 批量驳回低分 PENDING 技能（V34）。
     *
     * <p>backlog 有一万多条 PENDING，逐条审不现实。规则：{@code audit_status='PENDING'}
     * 且 {@code quality_score <= maxScore} → {@code REJECTED} + {@code is_active=0}。</p>
     *
     * <p>⛔ 必须带 limit（缺省 {@value #BULK_REJECT_DEFAULT_LIMIT}、上限 {@value #BULK_REJECT_MAX_LIMIT}）：
     * 不许一次 UPDATE 全表。先限量取 id，再按 ≤{@value #BULK_BATCH_SIZE} 一批更新。</p>
     *
     * @return 本次实际驳回条数
     */
    @AuditLog("evolution_bulk_reject")
    @PostMapping("/skills/bulk-reject")
    public Result<Integer> bulkReject(
            @RequestParam(required = false, defaultValue = "5") Integer maxScore,
            @RequestParam(required = false, defaultValue = "2000") Integer limit,
            HttpServletRequest request) {
        adminGuard.check(request);
        int score = maxScore == null ? 5 : maxScore;
        int n = (limit == null || limit < 1)
                ? BULK_REJECT_DEFAULT_LIMIT : Math.min(limit, BULK_REJECT_MAX_LIMIT);
        List<Long> ids = skillMapper.findPendingIdsByMaxScore(score, n);
        int rejected = 0;
        for (int i = 0; i < ids.size(); i += BULK_BATCH_SIZE) {
            List<Long> batch = ids.subList(i, Math.min(ids.size(), i + BULK_BATCH_SIZE));
            rejected += skillMapper.bulkRejectByIds(batch);
        }
        log.info("Bulk rejected {} PENDING skills (maxScore={}, limit={})", rejected, score, n);
        return Result.ok(rejected);
    }

    private static SkillVO toVo(EvolutionSkill s) {
        return new SkillVO(s.getId(), s.getSkillName(), s.getDescription(), s.getContent(),
                s.getQualityScore(), s.getAuditStatus(), s.getIsActive(),
                s.getSourceSessionId(), s.getCreatedAt());
    }
}
