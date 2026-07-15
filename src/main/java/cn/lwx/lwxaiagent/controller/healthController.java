package cn.lwx.lwxaiagent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 健康检查控制器：用于部署环境的存活探针。
@RestController // 标记为 REST 控制器，返回值直接作为响应体。
@RequestMapping("/health") // 基础路径，便于部署时做健康探测。
public class healthController {
    // 轻量健康检查端点，仅用于连通性探测。
    @GetMapping
    public String health() {
        // 边界：不做业务检查，只返回固定字符串。
        return "ok";
    }
}
