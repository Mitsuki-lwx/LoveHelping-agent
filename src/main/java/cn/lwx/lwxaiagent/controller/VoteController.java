package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import cn.lwx.lwxaiagent.evolution.SkillIngestor;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import cn.lwx.lwxaiagent.service.EvolutionService;
import cn.lwx.lwxaiagent.tenant.AdminGuard;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/evolution")
public class VoteController {

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
}
