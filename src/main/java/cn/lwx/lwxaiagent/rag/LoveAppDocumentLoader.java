package cn.lwx.lwxaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * <h2>LoveApp 文档加载器</h2>
 *
 * <p>这是一个 Spring 管理的组件类（用 @Component 标注），
 * 负责在 RAG 流程中<b>从项目资源目录加载 Markdown 文档</b>，
 * 并将其转换为 Spring AI 框架可识别的 Document 对象列表。</p>
 *
 * <h3>在 RAG 全流程中的位置与作用</h3>
 * <pre>
 * RAG 全流程：
 * ┌──────────────┐    ┌───────────────┐    ┌──────────────┐    ┌──────────┐    ┌──────────┐
 * │ 1.文档加载    │ → │ 2.文档切分     │ → │ 3.向量嵌入    │ → │ 4.存入向量库│ → │ 5.检索   │ → 6.LLM生成
 * │(本类负责)     │    │(TokenTextSplt) │    │(EmbeddingModel│    │(VectorStore)│    │(Retriever)│
 * └──────────────┘    └───────────────┘    └──────────────┘    └──────────┘    └──────────┘
 * </pre>
 *
 * <h3>核心功能</h3>
 * <ol>
 *   <li><b>文件扫描</b>：使用 Spring 的 ResourcePatternResolver 扫描
 *       classpath 下 {@code document/*.md} 的所有 Markdown 文件</li>
 *   <li><b>文档读取</b>：通过 Spring AI 的 MarkdownDocumentReader 读取 Markdown
 *       文件并解析为 Document 对象</li>
 *   <li><b>元数据注入</b>：为每个文档添加额外的元数据信息，包括：
 *     <ul>
 *       <li><b>filename</b>：文件名，用于标识文档来源</li>
 *       <li><b>status</b>：从文件名中提取的状态信息（文件名倒数第6到第4个字符）</li>
 *       <li><b>tenantId</b>：租户 ID，默认值为 "default"，用于多租户场景下的数据隔离</li>
 *     </ul>
 *   </li>
 *   <li><b>水平线分割</b>：配置 Markdown 阅读器在遇到水平线（---）时分拆文档</li>
 * </ol>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>采用构造器注入 ResourcePatternResolver，遵循 Spring 最佳实践</li>
 *   <li>使用 @Slf4j 进行日志记录，方便排查文档加载失败的原因</li>
 *   <li>异常处理采用 catch-log 方式，加载失败的文档不会阻塞整体流程</li>
 *   <li>文件名提取状态信息是业务约定：文件名倒数第6到第4个字符代表状态码</li>
 * </ul>
 *
 * @author lwx
 * @since 1.0
 * @see org.springframework.ai.reader.markdown.MarkdownDocumentReader
 * @see org.springframework.ai.document.Document
 */
@Component
@Slf4j
public class LoveAppDocumentLoader {

    /**
     * Spring 资源模式解析器（构造器注入）
     * 用于在 classpath 中扫描和加载资源文件，支持 Ant 风格的路径匹配模式
     */
    private final ResourcePatternResolver resourcePatternResolver;

    /**
     * <h3>构造器：注入资源模式解析器</h3>
     *
     * <p>通过构造器注入 ResourcePatternResolver，
     * 这是 Spring 提供的资源加载工具，可以按 Ant 风格模式（如 classpath:document/*.md）
     * 扫描和获取项目中的资源文件。</p>
     *
     * @param resourcePatternResolver Spring 资源模式解析器，由 Spring 容器自动注入
     */
    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * <h3>加载 classpath 下 document 目录中的所有 Markdown 文件</h3>
     *
     * <p>该方法执行以下步骤：</p>
     * <ol>
     *   <li>通过 ResourcePatternResolver 扫描 classpath:document/ 目录下所有 .md 文件</li>
     *   <li>遍历每个 Markdown 文件，创建 MarkdownDocumentReaderConfig 配置对象：
     *     <ul>
     *       <li><b>withHorizontalRuleCreateDocument(true)</b>：遇到 Markdown 水平线（---）
     *           时分割为独立文档，这样每个小节是一个独立的检索单位</li>
     *       <li><b>withIncludeCodeBlock(false)</b>：不包含代码块内容</li>
     *       <li><b>withIncludeBlockquote(false)</b>：不包含引用块内容</li>
     *       <li><b>withAdditionalMetadata("filename", fileName)</b>：添加文件名元数据</li>
     *       <li><b>withAdditionalMetadata("status", ...)</b>：从文件名中提取状态信息
     *           （例如文件名 lovehelping06ok.md 将提取 "ok" 作为状态）</li>
     *     </ul>
     *   </li>
     *   <li>使用 MarkdownDocumentReader 读取文件内容并解析为 Document 列表</li>
     *   <li>为每个 Document 添加 <b>tenantId</b> 元数据（默认为 "default"），
     *       用于多租户场景下的数据隔离</li>
     *   <li>将所有文档合并到一个列表中返回</li>
     * </ol>
     *
     * <h3>关于 status 元数据的提取</h3>
     * <p>文件名倒数第6到第4个字符被提取为 status 元数据。
     * 例如文件名 "lovehelping06ok.md"（共17个字符，不含扩展名14字符），
     * substring(14-6, 14-4) = substring(8, 10) 可能返回类似 "ok" 的状态标识。
     * 这个 status 会在 LoveAppRagCustomAdvisorFactory 中作为过滤条件使用。</p>
     *
     * <h3>异常处理</h3>
     * <p>当发生 IO 异常时（如文件不存在、权限不足等），方法会记录错误日志并返回已加载的部分文档列表，
     * 不会因为单个文件加载失败而中断整个流程。</p>
     *
     * @return 加载的所有 Markdown 文档的 Document 对象列表，
     *         每个 Document 包含文本内容和元数据（filename、status、tenantId）
     */
    public List<Document> loadMarkdowns(){
        List< Document> allDocuments = new ArrayList<>();
        try {
            // 扫描 classpath 下 document 目录中所有 .md 文件
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                // 构建 Markdown 文档阅读器的配置
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        // 遇到水平线（---）时分割文档，使每个章节成为独立的检索单位
                        .withHorizontalRuleCreateDocument(true)
                        // 不包含代码块内容（代码块通常不是自然语言，对检索贡献小）
                        .withIncludeCodeBlock(false)
                        // 不包含引用块内容
                        .withIncludeBlockquote(false)
                        // 添加文件名作为元数据，key 为 "filename"
                        .withAdditionalMetadata("filename", fileName)
                        // 从文件名中提取状态信息作为元数据
                        // 提取规则：文件名倒数第6到第4个字符，例如 "lovehelpingXXok.md" → "ok"
                        .withAdditionalMetadata("status",fileName.substring(fileName.length()-6,fileName.length()-4))
                        .build();
                // 使用配置创建 Markdown 文档阅读器
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                // 读取并解析文档
                List<Document> docs = reader.get();
                // 为每个文档添加租户 ID 元数据（默认 "default"），用于多租户场景下的数据隔离
                for (Document doc : docs) {
                    doc.getMetadata().putIfAbsent("tenantId", "default");
                }
                allDocuments.addAll(docs);
            }
        } catch (IOException e) {
            // 记录加载失败的错误日志，但不中断整体流程
            log.error("Failed to load Markdown documents", e);
        }
        return allDocuments;
    }


}
