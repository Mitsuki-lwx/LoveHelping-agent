package cn.lwx.lwxaiagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA 路由 fallback —— 将非 /api、非 /Love_app 的前端路由
 * 静默返回 index.html，让 Vue Router 接管。
 */
@Configuration
public class SPAFallbackConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        // 所有 SPA 路由都转发到 index.html
        // 注意：不要在这里添加 /api、/Love_app 等后端 API 路由
        registry.addViewController("/{path:[a-zA-Z][a-zA-Z\\d-]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:[a-zA-Z][a-zA-Z\\d-]*}/**")
                .setViewName("forward:/index.html");
    }
}
