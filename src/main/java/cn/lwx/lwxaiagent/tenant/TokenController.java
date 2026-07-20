package cn.lwx.lwxaiagent.tenant;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWT Token 签发端点（开发/测试用）。
 * <p>
 * 生产环境应该走独立的认证服务，这里只是方便本地测试。
 * <p>
 * 用法：
 * GET /tenant/token?userId=user_001&tenantId=tenant_01&role=USER
 * → 返回 JWT 字符串
 * <p>
 * 之后在请求头加上：
 * Authorization: Bearer <token>
 */
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
