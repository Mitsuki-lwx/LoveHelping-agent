package cn.lwx.lwxaiagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA route fallback -- silently returns index.html for non-/api, non-/Love_app frontend routes,
 * letting Vue Router handle them.
 */
@Configuration
public class SPAFallbackConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        // All SPA routes forward to index.html
        // Note: do not add /api, /Love_app and other backend API routes here
        registry.addViewController("/{path:[a-zA-Z][a-zA-Z\\d-]*}")
                .setViewName("forward:/index.html");
        registry.addViewController("/{path:[a-zA-Z][a-zA-Z\\d-]*}/**")
                .setViewName("forward:/index.html");
    }
}
