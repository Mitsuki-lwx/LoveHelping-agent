package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.tenant.JwtTokenProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class TokenController {

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @GetMapping("tenant/token")
    public String generateToken(
            @RequestParam(defaultValue = "user_001") String userId,
            @RequestParam(defaultValue = "tenant_01") String tenantId,
            @RequestParam(defaultValue = "USER") String role) {
        String token = jwtTokenProvider.generateToken(userId, tenantId, role);
        log.info("Token generated for user={}, tenant={}", userId, tenantId);
        return token;
    }
}
