package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h2>查询重写器（Query Rewriter）</h2>
 *
 * <p>这是一个 Spring 管理的组件类（用 @Component 标注），
 * 在 RAG 流程的<b>检索前阶段（Pre-Retrieval）</b>中负责
 * <b>使用 LLM 对用户的原始查询进行优化和重写</b>。</p>
 *
 * <h3>在 RAG 流程中的位置</h3>
 * <pre>
 * 用户原始查询 → [查询重写：本类] → 优化后的查询 → 向量库检索 → 相关文档 → LLM 生成回答
 *                     ↑
 *            检索前优化（Pre-Retrieval）
 * </pre>
 *
 * <h3>为什么需要查询重写？</h3>
 * <p>用户输入的查询往往存在以下问题：</p>
 * <ul>
 *   <li><b>口语化</b>：用户可能使用口语、俚语或不完整的句子</li>
 *   <li><b>模糊性</b>：查询可能过于模糊，缺少关键信息</li>
 *   <li><b>上下文缺失</b>：在多轮对话中，用户可能使用代词（"它"、"那个"），
 *       脱离上下文难以理解</li>
 *   <li><b>关键词缺失</b>：用户查询可能不包含文档中的关键词汇</li>
 * </ul>
 *
 * <p>查询重写通过 LLM 将这些问题查询转化为更适合检索的形式：</p>
 * <ul>
 *   <li>将口语化表达转为正式的书面表达</li>
 *   <li>补充隐含的语义信息</li>
 *   <li>提取和扩展关键概念</li>
 *   <li>生成更适合向量检索的查询文本</li>
 * </ul>
 *
 * <h3>工作原理</h3>
 * <p>RewriteQueryTransformer 是 Spring AI 框架提供的内置组件，
 * 其内部实现如下：</p>
 * <ol>
 *   <li>将原始查询发送给 ChatModel（LLM）</li>
 *   <li>LLM 根据内置的重写指令优化查询（消除歧义、补充信息、规范表达）</li>
 *   <li>返回重写后的查询文本</li>
 *   <li>使用重写后的查询进行向量库检索</li>
 * </ol>
 *
 * <h3>示例</h3>
 * <table border="1">
 *   <tr><th>原始查询</th><th>重写后的查询</th></tr>
 *   <tr><td>"怎么追她"</td><td>"如何追求心仪的女生，恋爱交往的方法和技巧"</td></tr>
 *   <tr><td>"他不理我了咋办"</td><td>"恋爱中对方不回复消息应该如何应对和处理"</td></tr>
 * </table>
 *
 * @author lwx
 * @since 1.0
 * @see org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer
 * @see org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer
 */
@Slf4j
@Component
public class QueryRewriter implements QueryTransformer {

    private final ChatModel chatModel;
    private final PromptVersion promptVersion;

    /**
     * 提示词版本（2026-09-21 引入）：{@code v1} 为历史版本，{@code v2} 为约束版。
     *
     * <p>为什么要留两版：v1 被实测劣化（"冷战筑墙怎么办"被概括成"吵架"、关键实体丢失、
     * 偶发注入猜测词），但**它是当前唯一被评测过的版本**。要证明"是提示词的问题、不是改写本身的问题"，
     * 就必须能在同一套 ground truth 上把两版并排跑出来——所以旧版不删，可切换。
     * 默认 v2（约束版更好，但不因此就打开 {@code enabled}，那要看实测）。</p>
     */
    public enum PromptVersion { V1, V2 }

    /**
     * v1（历史版）：<b>保留原文以便对照</b>——不要"顺手优化"它，它是对照组。
     *
     * <p>失效机理（2026-09-21 逐句核对）：第 1 条"规范为<b>简洁</b>的书面检索句"直接邀请**压缩**；
     * 第 3 条只说"不改变查询<b>意图</b>"——而意图不变不等于**实体**不变（把"冷战筑墙"概括成"吵架"，
     * 意图确实没变，检索实体却丢了）；两个示例本身就在示范"丢原词 + 加新词"
     * （「他冷战了我怎么办」的改写里，原文的"我怎么办"没了，"感情/沉默"是原文没有的）。</p>
     */
    private static final String REWRITE_PROMPT_V1 = """
            你是中文检索查询改写器。把用户查询改写成更适合向量检索的形式，并做“词表对齐”：
            1. 消除口语、补全指代与缺失信息，规范为简洁的书面检索句；
            2. 把抽象的说法扩展出知识库文档可能使用的同义/相关表述（用顿号并列，不解释）：
               例：「非暴力沟通的四要素」→「非暴力沟通 四要素 四步表达框架 观察 感受 需要 请求」
               例：「他冷战了我怎么办」→「感情 冷战 沉默 如何应对 修复 沟通」
            3. 不改变查询意图，不编造事实，不输出解释，只输出改写后的查询本身。
            """;

    /** v2（约束版）：**只许追加，不许改写或删减**；拿不准就原样输出。 */
    private static final String REWRITE_PROMPT_V2 = """
            你是中文检索查询改写器。输出会被直接送去向量检索，原查询里的词一个都不能丢。
            硬性规则（违反任何一条都算失败）：
            1. 输出必须以原查询【原样开头】——逐字保留，包括口语说法、代词、疑问语气和专有叫法。
               禁止概括、禁止替换原词、禁止“规范化”成更笼统的说法。
            2. 只在原查询【之后追加】知识库可能用到的同义/近义表述，用空格分隔；
               禁止引入原查询里没有的人名、书名、数字，也禁止另起一个话题。
            3. 拿不准就不追加，把原查询原样输出。宁可少加词，不可猜错。
            4. 只输出一行结果，不解释、不换行、不加引号。
            示例（注意原查询被完整保留）：
              「非暴力沟通的四要素」→「非暴力沟通的四要素 四步表达框架 观察 感受 需要 请求」
              「他冷战了我怎么办」→「他冷战了我怎么办 冷暴力 沉默 筑墙 如何应对 修复 沟通」
              「吵架了怎么和好」→「吵架了怎么和好」
            """;

    /**
     * 构造器：使用主模型（LlmGateway，ADR-7）执行改写。
     * 主模型本身具备重试/降级能力，改写调用失败时自动切备用供应商。
     *
     * @param chatModel 主聊天模型（@Primary = LlmGateway），用于执行查询重写
     */
    public QueryRewriter(ChatModel chatModel,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${app.rag.query-rewrite.prompt-version:v2}") String promptVersion) {
        this.chatModel = chatModel;
        this.promptVersion = parseVersion(promptVersion);
    }

    /** 解析版本串；无法识别时回落到 v2（约束版更安全），并记一条 WARN。 */
    private static PromptVersion parseVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return PromptVersion.V2;
        }
        try {
            return PromptVersion.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown query-rewrite prompt version '{}', falling back to V2", raw);
            return PromptVersion.V2;
        }
    }

    /** 当前生效的提示词版本（评测端点在响应里回显，便于确认跑的是哪一版）。 */
    public String promptVersionName() {
        return promptVersion.name();
    }

    private String prompt() {
        return promptVersion == PromptVersion.V1 ? REWRITE_PROMPT_V1 : REWRITE_PROMPT_V2;
    }

    /**
     * 实现 {@link QueryTransformer}：供 RetrievalAugmentationAdvisor 检索前调用（ADR-15）。
     */
    @Override
    public Query transform(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return query;
        }
        // 英文/非中文查询跳过中文改写（中文词表对齐指令对英文不稳，原查询更易命中）
        if (!containsCjk(query.text())) {
            return query;
        }
        try {
            String rewritten = ChatClient.builder(chatModel).build().prompt()
                    .system(prompt())
                    .user(query.text())
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                return query;
            }
            String trimmed = rewritten.trim();
            if (trimmed.length() > 120) {
                trimmed = trimmed.substring(0, 120);
            }
            return new Query(trimmed);
        } catch (Exception e) {
            log.warn("Query rewrite failed, use original query: {}", e.getMessage());
            return query;
        }
    }

    /**
     * 便捷方法：对文本查询重写（兼容旧调用方）。
     */
    public String doRewrite(String prompt) {
        return transform(new Query(prompt)).text();
    }

    private boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.codePointAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
