package cn.lwx.lwxaiagent.harness.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>热线号码的「副本闸门」（ADR-89）</h3>
 *
 * <p>⛔ <b>为什么要有这个测试</b>：转介文案一度有 <b>6 份副本</b>，其中两份<b>只有一条热线</b>。
 * 这不是靠"记得用 CrisisResources"能防住的 —— 我本人当天就漏了两次（先找到 4 处，
 * 复查才发现还有 2 处）。<b>靠记忆防不住的东西，必须靠机械闸门。</b></p>
 *
 * <p>规则很简单：<b>除 {@link CrisisResources} 外，任何源文件都不许出现热线号码字面量</b>。
 * 新增出站文案时，请写 {@code CrisisResources.referralText()} / {@code selfHarmOutputText()}，
 * 而不是把号码抄一遍。</p>
 */
@DisplayName("热线号码副本闸门：只允许 CrisisResources 持有号码（ADR-89）")
class HotlineSingleSourceTest {

    /** 必须唯一持有号码的文件（相对 src/main/java 的路径）。 */
    private static final String ALLOWED = "cn/lwx/lwxaiagent/harness/governance/CrisisResources.java";

    /** 号码特征：只取数字主干，避免被格式差异（全角/空格/短横线）绕过。 */
    private static final List<String> HOTLINE_DIGITS = List.of("4001619995", "01082951332");

    @Test
    void noSourceFileHardcodesHotlineNumbers() throws IOException {
        Path srcRoot = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(srcRoot))
                .as("测试需在项目根目录运行（src/main/java 必须可见）").isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(srcRoot)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = srcRoot.relativize(f).toString().replace('\\', '/');
                if (ALLOWED.equals(rel)) continue;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                // 归一化：去掉所有非数字字符后再找号码主干 —— 这样 "-"、" "、"（）" 等写法差异都拦得住
                String digitsOnly = text.replaceAll("[^0-9]", "");
                for (String num : HOTLINE_DIGITS) {
                    if (digitsOnly.contains(num)) {
                        offenders.add(rel + " 含 " + num);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("热线号码只允许出现在 %s —— 其余位置请改用 CrisisResources（曾因副本漂移出过「只有一条热线」的文案）",
                        ALLOWED)
                .isEmpty();
    }

    /** 闸门本身要有效：确认它真的能看见号码（否则"没命中"可能只是扫描坏了）。 */
    @Test
    void gateCanActuallySeeTheNumbers() throws IOException {
        Path allowed = Path.of("src", "main", "java", ALLOWED);
        assertThat(Files.isRegularFile(allowed)).as("唯一事实源文件必须存在").isTrue();
        String digitsOnly = Files.readString(allowed, StandardCharsets.UTF_8).replaceAll("[^0-9]", "");
        for (String num : HOTLINE_DIGITS) {
            assertThat(digitsOnly).as("闸门必须能在 CrisisResources 里看到 %s", num).contains(num);
        }
    }
}
