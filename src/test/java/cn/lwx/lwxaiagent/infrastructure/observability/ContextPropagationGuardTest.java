package cn.lwx.lwxaiagent.infrastructure.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-34 的覆盖面守护：<b>跨线程边界必须显式传递上下文</b>。
 *
 * <p>ADR-34 修好的是"已经发生"的归属断裂。这个测试守的是<b>将来</b>：线程池换了线程之后，
 * 父 trace 上下文与租户身份都不会自动跟过去（`ThreadLocal` 不跨线程，ADR-24 也已排除全局 hook）。
 * 新增线程池忘了挂 {@code TaskDecorator} 时，断裂会<b>静默复活</b>——没有任何运行时用例会失败。
 * 所以这里扫描源码表达不变式本身，对将来新增的池自动生效。</p>
 *
 * <p><b>如实标注局限</b>：两条断言都是<b>静态近似</b>——只看直接引用，不追方法调用链，
 * 也不做数据流分析。定位是 lint（挡住最常见的写法），不是证明。</p>
 */
class ContextPropagationGuardTest {

    private static final Path ROOT = Paths.get("src", "main", "java");

    /** 线程池创建点：{@code new ThreadPoolTaskExecutor()}（含中间空白）。 */
    private static final Pattern POOL_CREATION = Pattern.compile("new\\s+ThreadPoolTaskExecutor\\s*\\(\\s*\\)");
    /** 上下文传播装配点。 */
    private static final Pattern TASK_DECORATOR = Pattern.compile("setTaskDecorator\\s*\\(");
    private static final Pattern ASYNC = Pattern.compile("@Async\\s*\\(");
    /** 类成员（4 空格缩进）的方法签名行——用于回溯所属方法。 */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile("^ {4}(public|private|protected)\\s.*");
    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\(");

    /**
     * 允许"不挂装饰器"的池——不是"不用管"，而是"上下文由别处负责"。
     * 新增豁免必须同时更新本表与 {@code docs/phase6-leftovers/spec.md} §S1.3，评审可见。
     */
    private static final Map<String, String> DECORATOR_EXEMPT = Map.of(
            "cn/lwx/lwxaiagent/infrastructure/orchestration/graph/GraphExecutorConfig.java#graphExecutor",
            "GraphRunner 不走线程上下文：trace 父上下文经图状态（PIPELINE_TRACE_ID/SPAN_ID）显式重建，"
                    + "租户身份在 execute() 内显式 set/restore；再加装饰器会造成两套传播并存的错觉");

    @Test
    void everyApplicationThreadPoolPropagatesContextOrIsExplicitlyExempted() throws IOException {
        assumeTrue(Files.isDirectory(ROOT), "跳过：测试未在模块根目录运行");

        List<String> violations = new ArrayList<>();
        List<String> exempted = new ArrayList<>();
        int pools = 0;

        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!POOL_CREATION.matcher(lines.get(i)).find()) continue;
                pools++;
                String key = relative(file) + "#" + enclosingMethod(lines, i);
                String body = methodBody(lines, enclosingSignature(lines, i));
                if (TASK_DECORATOR.matcher(body).find()) continue;
                if (DECORATOR_EXEMPT.containsKey(key)) {
                    exempted.add(key);
                    continue;
                }
                violations.add(key + ":" + (i + 1));
            }
        }

        // 反恒真：扫描逻辑写坏（路径假设/正则失效）时必须报错，而不是静默全绿。
        assertTrue(pools >= 2,
                "守护失效：只扫到 " + pools + " 个线程池，但当前代码里应当有 2 个——请检查扫描逻辑或目录假设");
        assertTrue(violations.isEmpty(),
                "以下线程池没有显式传播上下文（父 trace 丢失、后台 span 自成新根；租户身份在该线程上为 null）："
                        + violations + " —— 请挂 TaskDecorator，或在 ContextPropagationGuardTest 的豁免表里登记理由");
        assertFalse(exempted.isEmpty(),
                "豁免表已过期：没有任何线程池被豁免，请检查豁免 key 的写法是否还与代码路径一致");
    }

    @Test
    void asyncMethodsDoNotReadThreadLocalTenantContext() throws IOException {
        assumeTrue(Files.isDirectory(ROOT), "跳过：测试未在模块根目录运行");

        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (Path file : javaFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (isComment(lines.get(i)) || !ASYNC.matcher(lines.get(i)).find()) continue;
                int signature = nextSignature(lines, i);
                if (signature < 0) continue;
                scanned++;
                String body = stripComments(methodBody(lines, signature));
                if (body.contains("TenantContext.")) {
                    violations.add(relative(file) + ":" + (i + 1) + " -> " + methodName(lines.get(signature)));
                }
            }
        }

        // 反恒真：一个 @Async 方法都没扫到时，说明注解或签名匹配失效。
        assertTrue(scanned >= 1,
                "守护失效：一个 @Async 方法都没扫到，但 SkillReflector.reflect 应当是——请检查扫描逻辑");
        assertTrue(violations.isEmpty(),
                "以下异步方法直接读了 TenantContext：跨线程后它是 null（静默、不报错），"
                        + "会把数据写到错误归属上。请把租户当参数传进来，或先 TenantContext.restore(capture())："
                        + violations);
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** 从某行向上回溯最近的类成员方法签名行；找不到返回该行自身。 */
    private static int enclosingSignature(List<String> lines, int from) {
        for (int i = from; i >= 0; i--) {
            if (METHOD_SIGNATURE.matcher(lines.get(i)).find()) return i;
        }
        return from;
    }

    private static int nextSignature(List<String> lines, int from) {
        for (int i = from + 1; i < lines.size(); i++) {
            if (METHOD_SIGNATURE.matcher(lines.get(i)).find()) return i;
        }
        return -1;
    }

    private static String enclosingMethod(List<String> lines, int poolLine) {
        int signature = enclosingSignature(lines, poolLine);
        return methodName(lines.get(signature));
    }

    private static String methodName(String signatureLine) {
        Matcher m = METHOD_NAME.matcher(signatureLine);
        String name = "?";
        while (m.find()) name = m.group(1);
        return name;
    }

    /** 从签名行起按大括号配平取方法体；签名行之后的第一个深度归零处收尾。 */
    private static String methodBody(List<String> lines, int signatureLine) {
        if (signatureLine < 0) return "";
        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean opened = false;
        for (int i = signatureLine; i < lines.size(); i++) {
            String line = lines.get(i);
            body.append(line).append('\n');
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') { depth++; opened = true; }
                else if (ch == '}') depth--;
            }
            if (opened && depth == 0) break;
        }
        return body.toString();
    }

    private static String stripComments(String body) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            if (!isComment(line)) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean isComment(String line) {
        String t = line.trim();
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
    }

    private static String relative(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }
}
