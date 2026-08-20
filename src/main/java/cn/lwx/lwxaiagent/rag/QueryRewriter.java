package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
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
@Component
public class QueryRewriter implements QueryTransformer {

    /**
     * 查询转换器（QueryTransformer）
     * 使用 RewriteQueryTransformer 实现，负责将原始查询改写为更适合检索的形式
     */
    private final QueryTransformer queryTransformer;

    /**
     * 构造器：使用主模型（LlmGateway，ADR-7）创建 RewriteQueryTransformer。
     * 主模型本身具备重试/降级能力，重写调用失败时自动切备用供应商。
     *
     * @param chatModel 主聊天模型（@Primary = LlmGateway），用于执行查询重写
     */
    public QueryRewriter(ChatModel chatModel) {
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        queryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(builder)
                .build();
    }

    /**
     * 实现 {@link QueryTransformer}：供 RetrievalAugmentationAdvisor 检索前调用（ADR-15）。
     */
    @Override
    public Query transform(Query query) {
        return queryTransformer.transform(query);
    }

    /**
     * 便捷方法：对文本查询重写（兼容旧调用方）。
     */
    public String doRewrite(String prompt) {
        return transform(new Query(prompt)).text();
    }
}
