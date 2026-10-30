package cn.lwx.lwxaiagent.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>AnalyticsProperties 默认值测试</h3>
 *
 * <p>钉住：默认前缀列表与 application.yml 的默认值一致（防两边漂移），
 * 且覆盖契约中列出的全部合成账号家族。</p>
 */
@DisplayName("AnalyticsProperties：测试账号排除前缀")
class AnalyticsPropertiesTest {

    /**
     * ⛔ 名字承诺"与 yml 一致"，就**真的去读 yml 比对**（此前只查了一份硬编码清单 + 写死的 size，
     * 改 yml 不会红 —— 那种测试挡不住"两处漂移"，而本仓最典型的复发缺陷就是它）。
     */
    @Test
    void defaults_matchYmlPrefixList() throws Exception {
        Path yml = Path.of("src", "main", "resources", "application.yml");
        assertThat(Files.isRegularFile(yml)).as("测试需在项目根目录运行").isTrue();
        String text = Files.readString(yml, StandardCharsets.UTF_8);
        Matcher m = Pattern
                .compile("exclude-username-prefixes:\\s*\\$\\{ANALYTICS_EXCLUDE_PREFIXES:([^}]*)\\}")
                .matcher(text);
        assertThat(m.find()).as("application.yml 里应能找到 exclude-username-prefixes 的默认值").isTrue();

        List<String> fromYml = Arrays.stream(m.group(1).split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();

        assertThat(AnalyticsProperties.defaultPrefixes())
                .as("Java 默认值与 application.yml 必须逐项一致（改一处漏一处 = 线上过滤口径不一致）")
                .containsExactlyElementsOf(fromYml);

        // 关键形态必须在（防止有人把清单清空还"一致"）
        assertThat(fromYml).contains("verify_", "load_", "mu_", "smoke_");
    }

    @Test
    void defaultInstance_carriesDefaults() {
        assertThat(new AnalyticsProperties().getExcludeUsernamePrefixes())
                .isEqualTo(AnalyticsProperties.defaultPrefixes());
    }

    @Test
    void customPrefixes_overrideDefaults() {
        AnalyticsProperties props = new AnalyticsProperties();
        props.setExcludeUsernamePrefixes(List.of("e2e_"));
        assertThat(props.getExcludeUsernamePrefixes()).containsExactly("e2e_");
    }
}
