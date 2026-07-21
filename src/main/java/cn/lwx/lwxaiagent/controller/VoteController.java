package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.service.EvolutionService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/evolution")
public class VoteController {

    private final EvolutionService evolutionService;

    public VoteController(EvolutionService evolutionService) {
        this.evolutionService = evolutionService;
    }

    @PostMapping("/vote")
    public Result<String> vote(@Valid @RequestBody EvolutionService.VoteRequest req) {
        evolutionService.vote(TenantContext.getTenantId(), req);
        return Result.ok("ok");
    }
}
