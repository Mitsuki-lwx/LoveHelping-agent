package cn.lwx.lwxaiagent.common;

/**
 * <h1>业务异常类</h1>
 *
 * <p>自定义的运行时异常，用于在业务逻辑中主动抛出异常，然后由
 * {@link GlobalExceptionHandler} 统一拦截处理，返回给前端一个格式化的错误响应。</p>
 *
 * <p><b>为什么不用 Java 自带的异常？</b></p>
 * <ul>
 *   <li>自带异常没有业务状态码（code），前端无法根据错误码做差异化处理</li>
 *   <li>通过 {@code @ExceptionHandler(BizException.class)} 可以精准捕获并返回统一格式的 {@link Result}</li>
 *   <li>在 Service 层中只需要 {@code throw new BizException(401, "未登录")} 即可，代码简洁</li>
 * </ul>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 在 Service 中抛出业务异常
 * if (user == null) {
 *     throw new BizException(404, "用户不存在");
 * }
 * // 使用默认状态码 400
 * throw new BizException("参数不能为空");
 * }</pre>
 *
 * @author lwx
 * @see GlobalExceptionHandler 全局异常处理器（会捕获此异常并返回 Result）
 * @see Result 统一返回体
 */
public class BizException extends RuntimeException {  // 继承 RuntimeException，无需在方法签名上声明 throws

    /**
     * 业务状态码，例如 400（参数错误）、401（未登录）、403（无权限）、404（未找到）
     * 前端可以根据不同的 code 值做不同的 UI 处理
     */
    private final int code;

    /**
     * 构造一个带自定义状态码和错误消息的业务异常
     *
     * @param code    业务状态码（建议使用 HTTP 状态码风格，便于理解）
     * @param message 给用户看的错误提示文案
     */
    public BizException(int code, String message) {
        super(message);   // 调用 RuntimeException 的构造器，设置异常的 detailMessage
        this.code = code; // 附加业务状态码
    }

    /**
     * 构造一个默认状态码（400）的业务异常
     * 适用于大多数"请求参数有误"的场景
     *
     * @param message 错误提示文案
     */
    public BizException(String message) {
        this(400, message); // 委托给上面的构造器，状态码默认 400（Bad Request）
    }

    /**
     * 获取业务状态码
     *
     * @return 业务状态码（如 400、401、403、404 等）
     */
    public int getCode() {
        return code;
    }
}
