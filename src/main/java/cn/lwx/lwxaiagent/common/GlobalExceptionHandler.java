package cn.lwx.lwxaiagent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * <h1>全局异常处理器</h1>
 *
 * <p>利用 Spring MVC 的 {@code @RestControllerAdvice} 机制，<b>统一拦截 Controller 层抛出的所有异常</b>，
 * 将其转换为格式化的 {@link Result} 响应返回给前端。</p>
 *
 * <p><b>处理流程（Spring 异常处理机制）：</b></p>
 * <ol>
 *   <li>Controller 或 Service 抛出异常</li>
 *   <li>Spring 的 DispatcherServlet 捕获到异常</li>
 *   <li>DispatcherServlet 查找所有 {@code @ExceptionHandler} 方法，按异常类型匹配最具体的那个</li>
 *   <li>执行匹配的方法，将返回值序列化为 HTTP 响应体</li>
 * </ol>
 *
 * <p><b>异常优先级：</b>Spring 会匹配继承链中最接近的那个处理器。
 * 例如 {@code MethodArgumentNotValidException} 继承自 {@code BindException}，
 * 但因为有专门的 handler，所以会走 {@link #handleValidation} 而不是 {@link #handleBind}。</p>
 *
 * <p><b>日志级别选择原则：</b></p>
 * <ul>
 *   <li>{@code log.warn}：客户端错误（参数校验失败、数据冲突等）——这是用户的问题，不需要告警</li>
 *   <li>{@code log.error}：服务器内部错误（空指针、数据库挂了等）——需要开发人员介入排查</li>
 * </ul>
 *
 * @author lwx
 * @see Result 统一返回体
 * @see BizException 业务异常
 */
@Slf4j               // Lombok 注解，自动生成 log 静态字段（使用 SLF4J 日志门面）
@RestControllerAdvice // = @ControllerAdvice + @ResponseBody，使方法的返回值直接序列化为 JSON 写入 HTTP 响应体
public class GlobalExceptionHandler {

    /**
     * 处理 @Valid / @Validated 校验失败时的异常
     *
     * <p><b>触发场景：</b>Controller 参数上加了 {@code @Valid} 或 {@code @Validated}，
     * 当传入的 JSON 字段不符合 Bean Validation 注解（如 {@code @NotBlank}、{@code @Size}）的规则时抛出。</p>
     *
     * <p><b>返回示例：</b></p>
     * <pre>{@code
     * {"code": 400, "message": "用户名不能为空; 密码长度不能少于6位", "data": null}
     * }</pre>
     *
     * @param e 方法参数校验失败异常，包含所有字段错误的详细信息
     * @return Result 对象，message 中包含所有校验失败的字段及其原因（用分号拼接）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class) // 指定这个方法只处理 MethodArgumentNotValidException
    @ResponseStatus(HttpStatus.BAD_REQUEST)                   // HTTP 响应状态码设为 400
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        // 从异常中取出 BindingResult → 获取所有字段错误 → 提取每个错误的默认消息 → 用分号拼接
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)        // 取出校验注解中定义的 message（如"用户名不能为空"）
                .collect(Collectors.joining("; "));         // 多个错误消息用分号分隔
        log.warn("Validation failed: {}", msg);             // 记录警告日志，便于排查是谁传了非法参数
        return Result.fail(400, msg);                       // 返回 400 + 合并后的错误消息
    }

    /**
     * 处理 Spring 数据绑定失败异常（如 GET 请求的 Query 参数绑定到对象时出错）
     *
     * <p><b>与 handleValidation 的区别：</b>{@code BindException} 是更通用的绑定异常，
     * 而 {@code MethodArgumentNotValidException} 是专门针对 {@code @Valid} 校验的异常。</p>
     *
     * @param e 绑定异常
     * @return 400 + 错误消息
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Bind failed: {}", msg);
        return Result.fail(400, msg);
    }

    /**
     * 处理数据库数据完整性冲突异常
     *
     * <p><b>触发场景：</b></p>
     * <ul>
     *   <li>插入重复的唯一键（Duplicate entry for key）</li>
     *   <li>违反外键约束</li>
     *   <li>NOT NULL 字段插入了 NULL</li>
     * </ul>
     *
     * @param e 数据完整性冲突异常（Spring 对 JDBC 异常的包装）
     * @return 409 Conflict + 中文提示
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)  // HTTP 409 表示资源冲突
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMessage());
        return Result.fail(409, "数据冲突，请检查后重试");
    }

    /**
     * 处理自定义业务异常 {@link BizException}
     *
     * <p>这是最常用的异常处理——开发者只需要在 Service 层
     * {@code throw new BizException(code, message)} 即可，
     * 这里会统一捕获并返回给前端。</p>
     *
     * <p>注意：这里<b>没有设置 {@code @ResponseStatus}</b>，
     * 因为 bizException.code 可能是 400、401、403 等任意值，
     * 需要动态设置。如需动态状态码，可以改用 {@code ResponseEntity}。</p>
     *
     * @param e 业务异常
     * @return Result，code 取自异常的 code 字段，message 取自异常的 message
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("Biz exception: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());  // 将异常中的状态码和消息原样返回
    }

    /**
     * 处理必填查询参数缺失（如 /chat/sse 不传 chatId）。
     * <p>Spring MVC 抛出的 {@link MissingServletRequestParameterException} 若无此处理器
     * 会落入兜底 {@code Exception} 处理器被误报为 500；这里显式映射为 400 参数错误。</p>
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return Result.fail(400, "缺少必填参数: " + e.getParameterName());
    }

    /**
     * <b>兜底异常处理</b>——处理所有未被上面方法捕获的异常
     *
     * <p>这是异常处理的"安全网"。所有意料之外的异常（NullPointerException、SQLException 等）
     * 都会被这里拦截，确保前端<b>不会看到裸奔的 500 错误页面</b>。</p>
     *
     * <p><b>安全考虑：</b>返回给前端的消息是通用的"服务器内部错误"，
     * 不会暴露敏感的堆栈信息（防止信息泄露）。真实的异常堆栈只记录在服务器日志中。</p>
     *
     * @param e 未知异常
     * @return 500 + 通用错误提示
     */
    @ExceptionHandler(Exception.class)  // 匹配所有未被更具体处理器处理的异常
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)  // HTTP 500
    public Result<Void> handleUnknown(Exception e) {
        log.error("Unexpected error", e);  // 使用 error 级别，且把异常对象本身也传给日志（会打印完整堆栈）
        return Result.error("服务器内部错误，请稍后重试");
    }
}
