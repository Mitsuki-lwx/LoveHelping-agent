package cn.lwx.lwxaiagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <h1>CORS 跨域配置 & 静态资源映射</h1>
 *
 * <p>实现 {@link WebMvcConfigurer} 接口来定制 Spring MVC 的行为，主要做两件事：</p>
 *
 * <h2>一、CORS（跨域资源共享）配置</h2>
 * <p><b>什么是跨域？</b>浏览器的同源策略（Same-Origin Policy）会阻止一个域名下的 JavaScript
 * 请求另一个域名的 API。协议、域名、端口三者中任何一个不同，就算跨域。</p>
 * <p><b>为什么要配置？</b>前后端分离开发时，前端（如 localhost:5173）和后端（如 localhost:8080）
 * 是不同的"源"，浏览器默认会拦截这种请求。需要在后端配置 CORS 来告诉浏览器"这个请求是合法的"。</p>
 *
 * <h2>二、静态资源映射</h2>
 * <p>将本地文件系统中的目录映射为 HTTP 可访问的 URL 路径。例如：
 * {@code /files/downloads/xxx.pdf} 映射到项目根目录下的 {@code downloads/} 文件夹。</p>
 *
 * @author lwx
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 配置跨域规则
     *
     * <p><b>各配置项说明：</b></p>
     * <ul>
     *   <li>{@code addMapping("/**")}：对所有 API 路径生效（/api/user、/api/chat 等都会应用此规则）</li>
     *   <li>{@code allowedOriginPatterns("*")}：允许所有域名的请求（开发阶段方便，生产环境应限制为具体域名）</li>
     *   <li>{@code allowCredentials(true)}：允许携带 Cookie/Session 等凭证信息（前后端分离时登录状态需要）</li>
     *   <li>{@code allowedMethods("GET", "POST", "DELETE", "PUT")}：只允许这 4 种 HTTP 方法跨域</li>
     *   <li>{@code allowedHeaders("*")}：允许所有请求头</li>
     *   <li>{@code exposedHeaders("*")}：允许前端 JS 读取所有响应头</li>
     * </ul>
     *
     * <p><b>安全提醒：</b>生产环境不要使用 {@code allowedOriginPatterns("*")}，
     * 应该限制为具体的前端域名，如 {@code "https://myapp.com"}。</p>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")                     // 匹配所有请求路径
                .allowedOriginPatterns("*")             // 允许任意域名的跨域请求
                .allowCredentials(true)                 // 允许发送 Cookie（用于保持登录状态）
                .allowedMethods("GET", "POST", "DELETE", "PUT")  // 允许的 HTTP 动词
                .allowedHeaders("*")                    // 允许请求携带任意请求头
                .exposedHeaders("*");                   // 允许前端 JS 读取响应中的任意响应头
    }

    /**
     * 配置静态资源映射
     *
     * <p>将本地磁盘上的文件目录映射为 HTTP URL，让前端可以通过 HTTP 直接访问/下载文件。</p>
     *
     * <p><b>映射规则：</b></p>
     * <ul>
     *   <li>{@code /files/**} → 项目根目录下的所有文件（用于访问上传的文件）</li>
     *   <li>{@code /files/downloads/**} → 项目根目录下的 {@code downloads/} 文件夹</li>
     * </ul>
     *
     * <p><b>示例：</b>访问 {@code http://localhost:8080/files/downloads/report.pdf}
     * 会映射到磁盘上的 {@code D:\project\downloads\report.pdf}</p>
     *
     * <p>注意：{@code user.dir} 是 JVM 的工作目录，即启动 Java 进程时所在的目录，
     * 路径分隔符统一替换为 "/" 以兼容不同操作系统。</p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 获取 JVM 工作目录（通常是项目根目录），并将反斜杠替换为正斜杠（跨平台兼容）
        String baseDir = System.getProperty("user.dir").replace("\\", "/");

        // 映射 项目根目录 → /files/** URL
        // "file:" 前缀告诉 Spring 这是本地文件系统路径，而不是 classpath 路径
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:" + baseDir + "/");

        // 映射 项目根目录/downloads → /files/downloads/** URL
        // 单独列出 downloads 目录是为了更精确地控制资源访问范围
        registry.addResourceHandler("/files/downloads/**")
                .addResourceLocations("file:" + baseDir + "/downloads/");
    }
}
