package cn.lwx.lwxaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADR-58：响应<b>信封拆封</b>的纯函数边界。
 *
 * <p>为什么值得留永久单测：这一段是"接新供应商"最容易被再次踩到的坑
 * （cline 网关的非流式响应是 {@code {"data":{choices...},"success":true}}，
 * 标准 OpenAI 客户端在顶层找不到 {@code choices} → {@code LlmGateway.call()} 全线 5000）。
 * 判据的关键不是"能拆"，而是<b>只在该拆的时候拆</b> —— 误拆正常响应或错误体会把
 * 一个好响应变成坏响应，且不会报错（静默变坏）。</p>
 */
class LlmProviderConfigEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unwrapsDataEnvelopeWithChoices() {
        String body = "{\"data\":{\"choices\":[{\"index\":0}],\"usage\":{}},\"success\":true}";
        String out = LlmProviderConfig.unwrap(body, "data", MAPPER);
        assertNotNull(out, "命中信封形状必须拆封");
        assertEquals("{\"choices\":[{\"index\":0}],\"usage\":{}}", out, "拆出来的应是内层对象（逐字）");
    }

    /** 已经是标准 OpenAI 形状 → 不得改写（否则把好响应改坏，且不报错）。 */
    @Test
    void leavesStandardResponseUntouched() {
        String body = "{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0}]}";
        assertNull(LlmProviderConfig.unwrap(body, "data", MAPPER), "顶层已有 choices，不该动");
    }

    /** 错误体（无 data，或 data 不是含 choices 的对象）→ 原样放行，让既有错误处理看到真话。 */
    @Test
    void leavesErrorBodyUntouched() {
        assertNull(LlmProviderConfig.unwrap("{\"error\":{\"message\":\"bad\"},\"success\":false}", "data", MAPPER));
        assertNull(LlmProviderConfig.unwrap("{\"data\":\"not-an-object\"}", "data", MAPPER), "data 非对象");
        assertNull(LlmProviderConfig.unwrap("{\"data\":{\"usage\":{}}}", "data", MAPPER), "内层无 choices");
    }

    /** 非 JSON / 空体 → 交给调用方原样放行，兼容层绝不能自己抛错。 */
    @Test
    void toleratesNonJsonAndEmpty() {
        assertNull(LlmProviderConfig.unwrap("", "data", MAPPER));
        assertNull(LlmProviderConfig.unwrap("   ", "data", MAPPER));
        assertNull(LlmProviderConfig.unwrap("data: [DONE]", "data", MAPPER));
        assertNull(LlmProviderConfig.unwrap("<html>502</html>", "data", MAPPER));
    }
}
