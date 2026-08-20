package cn.lwx.lwxaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h2>自定义 Token 文本分割器</h2>
 *
 * <p>这是一个 Spring 管理的组件类（用 @Component 标注），
 * 在 RAG 文档处理流程中负责<b>将长文档按 Token 数量分割为多个小段落</b>。</p>
 *
 * <h3>为什么需要文档分割？</h3>
 * <p>在 RAG 系统中，文档分割是一个重要的预处理步骤：</p>
 * <ul>
 *   <li><b>提高检索精度</b>：长文档包含多种主题，检索时可能匹配到不相关的部分。
 *       分割后每个小段落聚焦单一主题，检索更精准</li>
 *   <li><b>满足 LLM 上下文限制</b>：LLM 的上下文窗口有限，将过长的文档作为上下文
 *       可能超出 Token 限制。分割后每个片段大小可控</li>
 *   <li><b>向量质量</b>：Embedding 模型对长文本的向量表示可能不够精确，
 *       短文本的向量表示通常更能捕捉语义</li>
 * </ul>
 *
 * <h3>TokenTextSplitter 的工作原理</h3>
 * <p>TokenTextSplitter 是 Spring AI 提供的基于 Token 计数的文本分割器。
 * 它使用与 Embedding 模型相同的 Tokenizer 来统计 Token 数量，
 * 确保分割边界的 Token 计数准确。分割时会尽量在句子边界处断开，
 * 保持文本的语义完整性。</p>
 *
 * <h3>两种分割模式</h3>
 * <ul>
 *   <li><b>默认分割（splitDocuments）</b>：使用 Spring AI 的默认参数</li>
 *   <li><b>自定义分割（splitCustomized）</b>：手动指定所有参数，精细控制分割行为</li>
 * </ul>
 *
 * <h3>在 RAG 流程中的位置</h3>
 * <pre>
 * 文档加载 → 关键词丰富 → [文本分割：本类] → 向量嵌入 → 存入向量库
 *                            ↑
 *                   将长文档切分为小段落
 * </pre>
 *
 * @author lwx
 * @since 1.0
 * @see org.springframework.ai.transformer.splitter.TokenTextSplitter
 */
@Component
class MyTokenTextSplitter {

    /**
     * <h3>使用默认参数分割文档列表</h3>
     *
     * <p>使用 Spring AI 框架内置的默认参数创建 TokenTextSplitter。
     * 默认参数通常在合理范围内，适合大多数文档类型。</p>
     *
     * <p><b>注意：</b>由于使用默认参数，分割行为可能不适用于所有文档类型。
     * 对于特殊需求（如超长文档、代码文档等），建议使用 splitCustomized 方法。</p>
     *
     * @param documents 待分割的文档列表，每个 Document 包含完整的文本内容
     * @return 分割后的文档列表，每个文档的长度不超过默认的最大 Token 限制
     */
    public List<Document> splitDocuments(List<Document> documents) {
        // 使用 Spring AI 默认参数的 Token 文本分割器
        TokenTextSplitter splitter = new TokenTextSplitter();
        // 对所有文档执行分割操作
        return splitter.apply(documents);
    }

    /**
     * <h3>使用自定义参数分割文档列表（精细控制）</h3>
     *
     * <p>通过构造函数手动指定所有分割参数，实现对分割行为的精细控制：</p>
     *
     * <h3>参数详解</h3>
     * <table border="1">
     *   <tr><th>参数</th><th>值</th><th>含义</th></tr>
     *   <tr>
     *     <td><b>defaultChunkSize</b></td>
     *     <td>1000</td>
     *     <td>每个文档片段的最大 Token 数。1000 Tokens 约等于 750 个英文单词或 500 个中文字。
     *         这个大小在保留足够上下文的语义信息和适应 LLM 窗口限制之间取得了平衡</td>
     *   </tr>
     *   <tr>
     *     <td><b>minChunkSizeChars</b></td>
     *     <td>400</td>
     *     <td>每个文档片段的最小字符数。防止产生过小的碎片，
     *         过短的文本片段缺乏足够语义信息，向量表示质量差</td>
     *   </tr>
     *   <tr>
     *     <td><b>minChunkLengthToEmbed</b></td>
     *     <td>10</td>
     *     <td>进行嵌入的最小 Token 数。小于此长度的片段将被丢弃，
     *         因为极短文本（如只有几个词）的向量表示没有实际检索价值</td>
     *   </tr>
     *   <tr>
     *     <td><b>maxNumChunks</b></td>
     *     <td>5000</td>
     *     <td>单个文档最多分割为多少个片段。这是一个安全上限，
     *         防止异常大的文档产生过多碎片导致内存问题</td>
     *   </tr>
     *   <tr>
     *     <td><b>keepSeparator</b></td>
     *     <td>true</td>
     *     <td>是否保留分隔符。设置为 true 时，分割符（如句号、问号等）
     *         会被保留在文本中，有利于保持句子结构完整</td>
     *   </tr>
     *   <tr>
     *     <td><b>separators</b></td>
     *     <td>{'.', '?', '!', '\n'}</td>
     *     <td>分割符列表。分割器优先在这些符号处断开：
     *       <ul>
     *         <li><b>'.'</b>（句号）：英文句子边界</li>
     *         <li><b>'?'</b>（问号）：问句边界</li>
     *         <li><b>'!'</b>（感叹号）：感叹句边界</li>
     *         <li><b>'\n'</b>（换行符）：段落边界</li>
     *       </ul>
     *       在自然语言边界处分割，保持片段语义的完整性</td>
     *   </tr>
     * </table>
     *
     * <h3>分割策略</h3>
     * <p>分割器会尽量在分隔符处断开，确保每个片段不超过 defaultChunkSize（1000 Tokens）。
     * 如果在指定位置断开会导致片段过小（< 400字符），分割器可能会合并相邻片段。
     * 最终每个片段在 400字符 ~ 1000 Tokens 之间，保持了良好的语义单元大小。</p>
     *
     * @param documents 待分割的文档列表，每个 Document 包含完整的文本内容
     * @return 按自定义参数分割后的文档列表，每个片段大小在 400字符到1000 Tokens 之间
     */
    public List<Document> splitCustomized(List<Document> documents) {
        // 使用自定义参数创建 TokenTextSplitter：
        // 最大长度1000Token, 最小长度400字符, 最小嵌入长度10Token,
        // 最大片段数5000, 保留分隔符, 在句号/问号/感叹号/换行符处分割
        TokenTextSplitter splitter = new TokenTextSplitter(1000, 400, 10, 5000, true, List.of('.', '?', '!', '\n'));
        return splitter.apply(documents);
    }
}
