package cn.lwx.lwxaiagent.harness.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>输出安全护栏 —— AI 响应内容的最后一道安全检查</h1>
 *
 * <p><strong>核心作用：</strong>在 AI 生成响应后、返回给用户之前，对响应内容进行安全检查。
 * 确保 AI 不会给出有害建议，以及在用户表现出危机信号时 AI 给出了适当的干预回应。</p>
 *
 * <h2>⚠️ 安全重要提示</h2>
 * <p><b>此组件是 AI 安全的最后一道防线。</b>在情感咨询场景中，AI 的回应可能直接影响
 * 用户的心理状态和行为决策：</p>
 * <ul>
 *   <li>一句"你应该报复他"可能引发真实的暴力行为</li>
 *   <li>对自杀倾向的忽视可能错失危机干预的最佳时机</li>
 *   <li>鼓励跟踪或控制行为可能加剧亲密关系暴力</li>
 * </ul>
 *
 * <h2>两层输出安全机制</h2>
 *
 * <h3>第一层：危机干预缺失检测</h3>
 * <p>当用户在对话中表达了自残/自杀倾向时（输入中包含自杀、轻生等关键词），
 * 检查 AI 的回复是否包含了危机干预要素（如心理援助热线、专业求助建议等）。
 * 如果 AI 没有识别到用户的危机信号而给出了常规回复，系统会主动替换为危机干预信息。</p>
 *
 * <p><strong>检测逻辑：</strong></p>
 * <ol>
 *   <li>检查用户输入中是否包含自残/自杀关键词（{@link #SELF_HARM_KEYWORDS}）</li>
 *   <li>如果包含，检查 AI 回复中是否包含危机干预关键词（热线、专业、求助、心理）</li>
 *   <li>如果 AI 回复缺少危机干预内容 → <b>拦截输出，替换为危机干预信息和心理援助热线</b></li>
 * </ol>
 *
 * <h3>第二层：有害建议检测</h3>
 * <p>检查 AI 输出中是否包含可能引发危险行为的建议（{@link #HARMFUL_ADVICE_KEYWORDS}）：</p>
 * <ul>
 *   <li><b>暴力行为：</b>"你应该打..."、"动手教训"</li>
 *   <li><b>报复行为：</b>"报复他"、"以牙还牙"</li>
 *   <li><b>跟踪/控制行为：</b>"跟踪她"、"查他手机"、"控制对方"</li>
 * </ul>
 * <p>这些内容在健康的情感咨询中绝不应出现，如果检测到则直接拦截并返回安全提示。</p>
 *
 * <h2>与输入护栏的区别</h2>
 * <p>与 {@link InputGuardrail} 不同，当输出护栏检测到问题时：</p>
 * <ul>
 *   <li><b>会主动替换 AI 的原始响应</b>（返回预设的安全回复），而不是仅在日志中记录</li>
 *   <li>这确保了即使 LLM 产生了不安全的内容，用户也永远不会看到它</li>
 * </ul>
 *
 * @see GuardrailResult 安全护栏检查结果
 * @see InputGuardrail 输入安全护栏 —— 检查用户输入的合法性
 * @see GuardrailAdvisor 安全护栏拦截器 —— 调用本类进行输出检查
 */
@Slf4j
@Component
public class OutputGuardrail {

    /**
     * <h3>自残/自杀危险信号关键词列表</h3>
     *
     * <p>当用户输入中包含以下任一关键词时，表示用户可能处于心理危机状态，
     * 需要 AI 给出危机干预回应（如提供心理援助热线）。</p>
     *
     * <p>包含的关键词：自杀、自残、了结自己、不想活、了此残生、寻短见、
     * 结束生命、轻生、割腕、跳楼、吃药自杀</p>
     *
     * <p><strong>设计原则：</strong>宁可误报（false positive）也不可漏报（false negative）。
     * 在安全问题上，多一些过度谨慎是可以接受的代价。</p>
     */
    private static final List<String> SELF_HARM_KEYWORDS = List.of(
            "自杀", "自残", "了结自己", "不想活", "了此残生", "寻短见",
            "结束生命", "轻生", "割腕", "跳楼", "吃药自杀",
            "想死", "活不下去", "撑不下去", "不想再醒", "离开这个世界", "伤害自己", "结束自己"
    );

    /**
     * <h3>有害行为建议关键词列表</h3>
     *
     * <p>AI 输出中包含以下任一关键词时，表示 AI 正在给出可能危害用户或他人的建议，
     * 需要立即拦截。</p>
     *
     * <p>包含的检测模式：</p>
     * <ul>
     *   <li><b>直接暴力：</b>"你应该打"、"动手教训"</li>
     *   <li><b>报复行为：</b>"报复他"、"以牙还牙"</li>
     *   <li><b>侵犯隐私/控制：</b>"跟踪她"、"查他手机"、"控制对方"</li>
     * </ul>
     *
     * <p><strong>注意：</strong>此列表使用 {@link String#contains} 进行子串匹配，
     * 不需要精确匹配，只要 AI 输出中出现这些子串即触发拦截。</p>
     */
    private static final List<String> HARMFUL_ADVICE_KEYWORDS = List.of(
            "你应该打", "动手教训", "报复他", "以牙还牙",
            "跟踪她", "查他手机", "控制对方"
    );

    /**
     * <h3>心理危机干预 Fallback 回复</h3>
     *
     * <p>当检测到用户可能处于心理危机状态而 AI 没有给出适当的危机干预时，
     * 用此文本替换 AI 的原始响应。</p>
     *
     * <p>文本包含：</p>
     * <ul>
     *   <li>情感支持和安抚语句</li>
     *   <li><b>24 小时心理援助热线：</b>400-161-9995</li>
     *   <li><b>北京心理危机研究与干预中心：</b>010-82951332</li>
     *   <li>寻求专业心理咨询师帮助的建议</li>
     * </ul>
     *
     * <p><strong>热线号码说明：</strong></p>
     * <ul>
     *   <li>400-161-9995 —— 全国 24 小时心理援助热线（中国）</li>
     *   <li>010-82951332 —— 北京心理危机研究与干预中心</li>
     * </ul>
     * <p>这些号码应在定期审查中确认仍然有效。如号码变更，需及时更新。</p>
     */
    private static final String CRISIS_FALLBACK = """
            如果你正在经历痛苦，请记住你不是一个人。
            🆘 24小时心理援助热线：400-161-9995
            🆘 北京心理危机研究与干预中心：010-82951332
            请寻求专业心理咨询师的帮助，他们能提供更系统的支持。
            """;

    /**
     * <h3>执行输出安全检查</h3>
     *
     * <p>检查 AI 生成的输出内容是否安全，同时结合用户输入上下文进行综合判断。</p>
     *
     * <h4>检查流程（按优先级）：</h4>
     * <ol>
     *   <li><b>空输出检查：</b>如果 AI 输出为 null 或空白，直接通过（没有内容需要检查）</li>
     *   <li><b>危机干预缺失检测：</b>
     *     <ul>
     *       <li>检查用户输入中是否有自杀/自残关键词</li>
     *       <li>如果有，检查 AI 输出中是否包含危机干预关键词（热线、专业、求助、心理）</li>
     *       <li>如果 AI 输出缺少这些关键词 → <b>拦截</b>，用危机干预信息替换</li>
     *     </ul>
     *   </li>
     *   <li><b>有害建议检测：</b>
     *     <ul>
     *       <li>遍历有害建议关键词列表</li>
     *       <li>如果 AI 输出包含任一关键词 → <b>拦截</b>，返回安全提示</li>
     *     </ul>
     *   </li>
     *   <li>全部通过 → 返回 {@link GuardrailResult#pass()}</li>
     * </ol>
     *
     * <h4>日志策略：</h4>
     * <ul>
     *   <li>所有拦截事件均记录 WARN 级别日志，包含具体的拦截原因</li>
     *   <li>危机干预缺失的日志中明确标注 "user mentioned self-harm but AI missed crisis response"</li>
     *   <li>有害建议的日志中列出触发的具体关键词，便于后续分析和模型调优</li>
     * </ul>
     *
     * @param output     AI 生成的输出文本（需要检查的内容）
     * @param userInput  用户的原始输入文本（用于上下文判断，如检查用户是否表达了危机信号）
     * @return 安全检查结果。{@link GuardrailResult#pass()} 表示输出安全，
     *         {@link GuardrailResult#block} 表示输出不安全需要替换
     */
    public GuardrailResult check(String output, String userInput) {
        if (output == null || output.isBlank()) return GuardrailResult.pass();

        String userContext = userInput != null ? userInput : "";

        // ===== 第一层：危机干预缺失检测 =====
        // 用户提到了自残/自杀关键词
        boolean userMentionsSelfHarm = SELF_HARM_KEYWORDS.stream()
                .anyMatch(userContext::contains);
        // 但 AI 回复中缺少危机干预内容（没有热线、专业、求助、心理等关键词）
        boolean aiMissingCrisisResponse = userMentionsSelfHarm
                && !output.contains("热线") && !output.contains("专业")
                && !output.contains("求助") && !output.contains("心理");

        if (aiMissingCrisisResponse) {
            log.warn("Output guardrail: user mentioned self-harm but AI missed crisis response");
            return GuardrailResult.block("missing_crisis_response", CRISIS_FALLBACK);
        }

        // ===== 第二层：有害建议检测 =====
        for (String kw : HARMFUL_ADVICE_KEYWORDS) {
            if (output.contains(kw)) {
                log.warn("Output guardrail: harmful advice detected: '{}'", kw);
                String fallback = "我无法提供此类建议。在亲密关系中，暴力、报复或控制行为都不是解决问题的健康方式，建议双方冷静沟通或寻求专业调解。";
                return GuardrailResult.block("harmful_advice", fallback);
            }
        }

        return GuardrailResult.pass();
    }
}
