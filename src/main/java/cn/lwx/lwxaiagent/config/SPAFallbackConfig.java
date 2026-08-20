package cn.lwx.lwxaiagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <h1>SPA（单页应用）路由回退配置</h1>
 *
 * <p><b>问题背景：</b>Vue/React 等前端 SPA 框架使用前端路由（Vue Router / React Router），
 * 页面跳转完全在前端完成。但用户如果<b>直接刷新页面</b>或<b>在地址栏手动输入 URL</b>，
 * 浏览器会向后端发送请求。后端没有对应的接口，就会返回 404。</p>
 *
 * <p><b>解决方案：</b>将所有非 API 路径的请求都转发到 {@code index.html}，
 * 让 Vue Router 接管路由，在前端正确处理页面跳转。</p>
 *
 * <p><b>转发逻辑：</b></p>
 * <ol>
 *   <li>用户访问 {@code /login}（一个 Vue 前端路由）</li>
 *   <li>后端没有 {@code /login} 接口，但匹配到本类的路由规则</li>
 *   <li>Spring MVC 转发到 {@code /index.html}</li>
 *   <li>浏览器加载 index.html → 加载 Vue 应用 → Vue Router 识别 /login 路由 → 渲染登录页面</li>
 * </ol>
 *
 * <p><b>排除了哪些路径？</b>路由规则使用正则表达式 {@code [a-zA-Z][a-zA-Z\d-]*}，
 * 只匹配纯字母开头的路径。这意味着：</p>
 * <ul>
 *   <li>❌ {@code /api/login} 不会被转发（包含斜杠，不是单层路径）</li>
 *   <li>❌ {@code /Love_app/chat} 不会被转发（包含斜杠）</li>
 *   <li>✅ {@code /login} 会被转发到 index.html</li>
 *   <li>✅ {@code /dashboard} 会被转发到 index.html</li>
 * </ul>
 */
@Configuration
public class SPAFallbackConfig implements WebMvcConfigurer {

    /**
     * 添加视图控制器，将 SPA 路由转发到 index.html
     *
     * <p><b>两条路由规则：</b></p>
     * <ol>
     *   <li>{@code /{path}}：匹配单层路径，如 {@code /login}、{@code /dashboard}</li>
     *   <li>{@code /{path}/**}：匹配多层嵌套路径，如 {@code /user/profile/edit}</li>
     * </ol>
     *
     * <p><b>正则解读：</b>{@code [a-zA-Z][a-zA-Z\d-]*} 表示：</p>
     * <ul>
     *   <li>首字符必须是字母（a-z 或 A-Z）</li>
     *   <li>后续字符可以是字母、数字或连字符（0 个或多个）</li>
     *   <li>这保证了像 {@code /api}、{@code /Love_app} 这类"非前端路由"不会被误转发</li>
     * </ul>
     *
     * <p><b>注意：</b>不要在此注册 {@code /api}、{@code /Love_app} 等后端 API 路由，
     * 否则会覆盖 Controller 的映射，导致接口 404。</p>
     */
    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        // 规则1：匹配单层非 API 路径（如 /login、/about），转发到 index.html
        registry.addViewController("/{path:[a-zA-Z][a-zA-Z\\d-]*}")
                .setViewName("forward:/index.html");

        // 规则2：匹配多层嵌套路径（如 /user/profile、/order/detail/123），转发到 index.html
        registry.addViewController("/{path:[a-zA-Z][a-zA-Z\\d-]*}/**")
                .setViewName("forward:/index.html");
    }
}
