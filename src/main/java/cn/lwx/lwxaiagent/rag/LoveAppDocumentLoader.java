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
                        // 不按水平线（---）切碎：整篇交 ParentChildDocumentTransformer 做父子切分。
                        // 旧行为按 "---" 切成小节后，每节过短，导致父块聚合不出 1500 字符的
                        // 上下文、子块平均仅 46 字符（检索注入的是碎片 → 模型只能答"我不知道"）。
                        .withHorizontalRuleCreateDocument(false)
                        // 不包含代码块内容（代码块通常不是自然语言，对检索贡献小）
                        .withIncludeCodeBlock(false)
                        // 不包含引用块内容
                        .withIncludeBlockquote(false)
                        // 添加文件名作为元数据，key 为 "filename"
                        .withAdditionalMetadata("filename", fileName)
                        // status 不再从文件名截取固定位（该约定只对 lovehelpingXXok.md 之类
                        // 英文老文件有效，对中文文件名产出"边界/性强"等无意义碎片）；
                        // 当前检索无 status 过滤消费者，统一置 "active"
                        .withAdditionalMetadata("status", "active")
                        .build();
                // 使用配置创建 Markdown 文档阅读器
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                // 读取并解析文档
                List<Document> docs = reader.get();
                // 为每个文档添加租户 ID 元数据（默认 "default"），用于多租户场景下的数据隔离
                for (Document doc : docs) {
                    doc.getMetadata().putIfAbsent("tenantId", "default");
                    // content_hash（ADR-15 增量更新）：文档级 SHA-256，供增量同步识别变更
                    doc.getMetadata().put("doc_hash", hash(doc.getText()));
                }
                allDocuments.addAll(docs);
            }
        } catch (IOException e) {
            // 记录加载失败的错误日志，但不中断整体流程
            log.error("Failed to load Markdown documents", e);
        }
        return mergeByFile(allDocuments);
    }

    /**
     * 按文件合并回整篇（ADR-15 父子索引前提）。
     * <p>MarkdownDocumentReader 会按标题层级产出多个 Document（实测 67 篇 → 453 段，
     * 每段仅百余字）。若直接交给 {@code ParentChildDocumentTransformer}，父块聚合不出
     * 1500 字符的完整上下文（实测库内 parent_text 平均仅 108 字符），small-to-large 失效——
     * 检索注入的是碎片，模型只能答"我不知道"。</p>
     * <p>此处按 filename 归并把各段拼回整篇（保留首个段的 metadata，doc_hash 改为整篇 hash），
     * 让 transformer 能按段落聚合出真正的大父块。</p>
     */
    private List<Document> mergeByFile(List<Document> docs) {
        java.util.LinkedHashMap<String, List<Document>> byFile = new java.util.LinkedHashMap<>();
        for (Document d : docs) {
            String file = String.valueOf(d.getMetadata().get("filename"));
            byFile.computeIfAbsent(file, k -> new java.util.ArrayList<>()).add(d);
        }
        List<Document> merged = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, List<Document>> e : byFile.entrySet()) {
            List<Document> parts = e.getValue();
            StringBuilder text = new StringBuilder();
            for (Document p : parts) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    text.append(t.trim()).append("\n\n");
                }
            }
            String whole = text.toString().trim();
            if (whole.isEmpty()) {
                continue;
            }
            Document doc = new Document(whole, new java.util.HashMap<>(parts.get(0).getMetadata()));
            doc.getMetadata().put("doc_hash", hash(whole)); // 增量同步以整篇 hash 为准
            merged.add(doc);
        }
        log.info("Markdown loaded: {} sections merged into {} documents", docs.size(), merged.size());
        return merged;
    }

    private static String hash(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text == null ? new byte[0] : text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
