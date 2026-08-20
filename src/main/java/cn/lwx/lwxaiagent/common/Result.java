package cn.lwx.lwxaiagent.common;

/**
 * <h1>统一 HTTP 响应体</h1>
 *
 * <p>使用 Java 14+ 的 <b>Record</b> 类型（不可变数据载体），表示项目中所有 API 接口的统一返回格式。</p>
 *
 * <p><b>为什么用 Record 而不是普通 Class？</b></p>
 * <ul>
 *   <li><b>不可变性</b>：Record 的所有字段都是 {@code final} 的，创建后无法修改，线程安全</li>
 *   <li><b>简洁</b>：自动生成构造器、getter、equals、hashCode、toString，无需手写样板代码</li>
 *   <li><b>语义明确</b>：Record 本身就是"数据载体"的语义，非常适合 DTO/VO 场景</li>
 * </ul>
 *
 * <p><b>返回 JSON 示例：</b></p>
 * <pre>{@code
 * // 成功响应
 * {"code": 200, "message": "success", "data": {"id": 1, "name": "张三"}}
 *
 * // 失败响应
 * {"code": 400, "message": "用户名不能为空", "data": null}
 *
 * // 错误响应
 * {"code": 500, "message": "服务器内部错误，请稍后重试", "data": null}
 * }</pre>
 *
 * @param <T>     响应数据的类型（泛型），可以是任意类型，如 String、User、List 等
 * @param code    HTTP 风格的状态码：200=成功，400=客户端错误，500=服务端错误
 * @param message 给前端/用户看的提示文案
 * @param data    实际返回的数据载荷，失败时为 null
 *
 * @author lwx
 */
public record Result<T>(int code, String message, T data) {

    /**
     * 返回一个成功的响应，带数据
     *
     * <p><b>使用场景：</b>查询成功、更新成功等需要返回数据给前端的场景</p>
     *
     * @param data 要返回给前端的数据（可以是任意类型）
     * @param <T>  数据的类型
     * @return Result 对象，code=200，message="success"
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 返回一个成功的响应，不带数据
     *
     * <p><b>使用场景：</b>删除成功、无需返回数据的操作</p>
     *
     * @param <T> 数据类型（此时为 Void 或任意）
     * @return Result 对象，code=200，message="success"，data=null
     */
    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    /**
     * 返回一个自定义状态码的失败响应
     *
     * <p><b>使用场景：</b>需要精确控制错误码的场景（如 401 未登录、403 无权限、404 不存在）</p>
     *
     * @param code    业务状态码（建议与 HTTP 状态码保持一致）
     * @param message 错误提示文案
     * @param <T>     数据类型
     * @return Result 对象，data=null
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 返回默认状态码 400 的失败响应
     *
     * <p><b>使用场景：</b>参数校验失败、请求格式不对等一般性客户端错误</p>
     *
     * @param message 错误提示文案
     * @param <T>     数据类型
     * @return Result 对象，code=400，data=null
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(400, message, null);
    }

    /**
     * 返回状态码 500 的服务器内部错误响应
     *
     * <p><b>使用场景：</b>服务器内部异常（数据库挂了、服务调用失败等），
     * 通常由 {@link GlobalExceptionHandler#handleUnknown} 调用</p>
     *
     * @param message 错误提示文案（不要暴露内部细节，用通用提示）
     * @param <T>     数据类型
     * @return Result 对象，code=500，data=null
     */
    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}
