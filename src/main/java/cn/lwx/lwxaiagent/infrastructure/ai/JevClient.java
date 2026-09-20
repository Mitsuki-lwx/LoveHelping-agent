package cn.lwx.lwxaiagent.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Jev（TypeSafe AI / System One）客户端 —— 只做<b>窄的结构化判定</b>。
 *
 * <p>官方定位（{@code docs.typesafe.ai/concepts/how-to-build-with-system-one}）：
 * "代码拥有流程，AI 只做窄的、结构化判定"；并且"答案永远被约束在你给的选项里，
 * 代码不需要从生成的散文里取值"。本类据此只用 {@code score} 与 {@code noul} 两种类型，
 * 调用方拿到的都是受约束的值，而不是需要正则去抠的自由文本。</p>
 *
 * <p>失败语义：任何异常/非 200/缺字段都返回 {@link Optional#empty()} 并记一趟 WARN 单行
 * （ADR-33 降噪口径），<b>绝不抛给调用方</b>——由调用方决定回退。</p>
 */
@Slf4j
@Component
public class JevClient {

    /** 情绪档位：顺序即分值顺序，与产品既有 -2..2 一一对应（下标 - 2）。 */
    public static final List<String> MOOD_LEVELS = List.of(
            "非常糟糕或处于危机：绝望、想不开、崩溃",
            "低落、难过、沮丧",
            "平静、中立、没有明显起伏",
            "有起色、稍微好一些",
            "明显变好、积极、有希望");

    private static final int STATE_MAX_CHARS = 1500;

    private final JevProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public JevClient(JevProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /** 判定结果：档位下标（0 起）+ 档位描述。 */
    public record Mood(int level, String label) {
        /** 映射到产品既有分值域 -2..2。 */
        public int toScore() {
            return level - (MOOD_LEVELS.size() / 2);
        }
    }

    /** 是否具备调用条件（未启用/无密钥时不发请求）。 */
    public boolean available() {
        return props.isEnabled() && props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /**
     * Score 判定：档位下标 + 档位描述。
     *
     * @param instructions 完整的问题（必须自带上下文；问题 id 不会发给模型）
     * @param stateField   状态里承载被判定文本的字段名，instructions 里用反引号引用它
     * @param stateValue   被判定的文本
     */
    public Optional<Mood> score(String instructions, String stateField, String stateValue) {
        ObjectNode question = mapper.createObjectNode();
        question.put("type", "score");
        question.put("instructions", instructions);
        ArrayNode levels = question.putArray("criteria");
        MOOD_LEVELS.forEach(levels::add);
        ObjectNode questions = mapper.createObjectNode();
        questions.set("mood", question);

        Optional<JsonNode> all = answers(stateField, stateValue, questions);
        if (all.isEmpty()) return Optional.empty();
        return parseMood(all.get().path("mood"));
    }

    /**
     * Noul 判定：返回"是"的概率（0~1）。
     *
     * <p>官方口径：{@code noul} 适合"干净的 yes/no，且概率本身就是有用信号"的场景，
     * 它没有单独的 confidence。</p>
     */
    public Optional<Double> noul(String questionId, String instructions,
                                 String trueMeaning, String falseMeaning,
                                 String stateField, String stateValue) {
        ObjectNode question = mapper.createObjectNode();
        question.put("type", "noul");
        question.put("instructions", instructions);
        ObjectNode criteria = question.putObject("criteria");
        criteria.put("true", trueMeaning);
        criteria.put("false", falseMeaning);
        ObjectNode questions = mapper.createObjectNode();
        questions.set(questionId, question);

        Optional<JsonNode> all = answers(stateField, stateValue, questions);
        if (all.isEmpty()) return Optional.empty();
        JsonNode value = all.get().path(questionId).get("noul");
        if (value == null || !value.isNumber()) {
            log.warn("jev noul failed: answer has no numeric noul");
            return Optional.empty();
        }
        return Optional.of(value.asDouble());
    }

    private Optional<Mood> parseMood(JsonNode answer) {
        JsonNode score = answer.get("score");
        if (score == null || !score.isNumber()) {
            log.warn("jev score failed: answer has no numeric score");
            return Optional.empty();
        }
        int level = (int) Math.round(score.asDouble());
        if (level < 0 || level >= MOOD_LEVELS.size()) {
            log.warn("jev score failed: level out of range {}", score.asDouble());
            return Optional.empty();
        }
        // 官方把每个档位编号原样回在 legend 里；优先用它的描述，取不到再用本地常量
        String label = answer.path("legend").path(String.valueOf(level)).asText(MOOD_LEVELS.get(level));
        return Optional.of(new Mood(level, label));
    }

    /** 发一次请求并返回 {@code answers} 节点；任何失败返回空。 */
    private Optional<JsonNode> answers(String stateField, String stateValue, ObjectNode questions) {
        if (!available()) return Optional.empty();
        try {
            ObjectNode state = mapper.createObjectNode();
            state.put(stateField, abbreviate(stateValue));

            ObjectNode body = mapper.createObjectNode();
            body.set("state", state);
            body.put("model", props.getModel());
            body.set("questions", questions);

            String endpoint = props.getBaseUrl().replaceAll("/+$", "") + "/v1/systemone";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("jev call failed: http={}", response.statusCode());
                return Optional.empty();
            }
            return Optional.of(mapper.readTree(response.body()).path("answers"));
        } catch (Exception e) {
            // 超时/连接失败/解析失败一律返回空；单行 WARN，不打堆栈（ADR-33）
            log.warn("jev call failed: {}", e.getClass().getSimpleName() + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= STATE_MAX_CHARS ? trimmed : trimmed.substring(0, STATE_MAX_CHARS);
    }
}
