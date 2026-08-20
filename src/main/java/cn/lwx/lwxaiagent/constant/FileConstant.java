package cn.lwx.lwxaiagent.constant;

/**
 * <h1>文件操作相关常量</h1>
 *
 * <p>集中定义项目中文件操作相关的常量，便于统一管理和修改。</p>
 *
 * <p><b>为什么使用 interface 定义常量？</b>在 Java 中，interface 中的字段默认是
 * {@code public static final} 的，常用来定义纯常量集合（Constant Interface 模式）。</p>
 *
 * <p>注意：这是一种常见的 Java 惯用法，但也有争议。更好的实践是使用
 * {@code public final class} + 私有构造器来定义常量，防止被实现（implement）而污染接口。</p>
 */
public interface FileConstant {

    /**
     * 文件保存的根目录
     *
     * <p>值取自 JVM 系统属性 {@code user.dir}，即启动 Java 进程时的工作目录（通常是项目根目录）。</p>
     *
     * <p><b>示例路径：</b>{@code D:\java\lwx-ai-agent}</p>
     *
     * <p>文件操作工具（如 {@code FileOperationTool}）会基于这个目录进行文件的读写操作。</p>
     */
    String FILE_SAVE_DIR = System.getProperty("user.dir");
}
