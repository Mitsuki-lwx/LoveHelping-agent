package cn.lwx.lwxaiagent.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <h1>统一响应体 Result 的单元测试</h1>
 *
 * <p>测试 {@link Result} Record 类中所有静态工厂方法的行为：</p>
 * <ul>
 *   <li>{@code Result.ok(data)} —— 成功响应，带数据</li>
 *   <li>{@code Result.ok()} —— 成功响应，不带数据</li>
 *   <li>{@code Result.fail(code, message)} —— 自定义状态码失败</li>
 *   <li>{@code Result.fail(message)} —— 默认 400 失败</li>
 *   <li>{@code Result.error(message)} —— 服务器 500 错误</li>
 * </ul>
 *
 * <p>这些测试确保 API 返回的 JSON 格式始终统一（{code, message, data} 三元组）。</p>
 */
class ResultTest {

    /**
     * 测试成功响应带数据的情况
     * 验证：code=200, message="success", data 是要返回的数据
     */
    @Test
    void okWithData() {
        Result<String> r = Result.ok("hello");
        assertEquals(200, r.code());        // 状态码必须是 200
        assertEquals("success", r.message()); // 消息必须是 "success"
        assertEquals("hello", r.data());     // 数据必须完整返回
    }

    /**
     * 测试成功响应不带数据的情况（如删除操作）
     * 验证：code=200, data=null
     */
    @Test
    void okWithoutData() {
        Result<String> r = Result.ok();
        assertEquals(200, r.code());
        assertNull(r.data());               // 无数据时 data 应为 null
    }

    /**
     * 测试自定义状态码的失败响应
     * 适用于需要精确控制 HTTP 状态码的场景（如 401 未登录、403 无权限）
     */
    @Test
    void failWithCodeAndMessage() {
        Result<String> r = Result.fail(400, "bad request");
        assertEquals(400, r.code());        // 状态码应等于传入的值
        assertEquals("bad request", r.message()); // 消息应等于传入的值
        assertNull(r.data());               // 失败时 data 为 null
    }

    /**
     * 测试默认 400 状态码的失败响应
     * fail(String) 是 fail(400, String) 的快捷方式
     */
    @Test
    void failDefault400() {
        Result<String> r = Result.fail("something wrong");
        assertEquals(400, r.code());        // 默认状态码为 400
        assertEquals("something wrong", r.message());
    }

    /**
     * 测试服务器内部错误（500）响应
     * 验证：code=500, message 是传入的错误消息
     */
    @Test
    void errorIs500() {
        Result<String> r = Result.error("server error");
        assertEquals(500, r.code());        // 服务器错误状态码固定为 500
        assertEquals("server error", r.message());
    }
}
