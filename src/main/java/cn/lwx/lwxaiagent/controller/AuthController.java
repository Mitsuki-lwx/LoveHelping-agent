package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.tenant.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private UserService userService;

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String tenantId = body.getOrDefault("tenantId", "default");
        String role = body.getOrDefault("role", "USER");

        if (username == null || password == null || username.isBlank() || password.isBlank()) {
            return Map.of("success", false, "message", "用户名和密码不能为空");
        }

        try {
            String token = userService.register(username, password, tenantId, role);
            return Map.of("success", true, "token", token, "username", username);
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || password == null) {
            return Map.of("success", false, "message", "用户名和密码不能为空");
        }

        try {
            String token = userService.login(username, password);
            return Map.of("success", true, "token", token, "username", username);
        } catch (RuntimeException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }
}
