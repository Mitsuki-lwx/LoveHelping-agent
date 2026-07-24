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
 * LoveAppDocumentLoader is a component class for loading Markdown documents.
 * It uses Spring's ResourcePatternResolver to scan the specified directory (classpath:document
 * ) in the project for all Markdown files and reads their content as Document object lists.
 * This class also supports adding extra metadata to each document, such as file
 * name and status information. If any IO exceptions occur during loading, error logs are recorded.
 */
@Component
@Slf4j
public class LoveAppDocumentLoader {
    // Inject resource resolver, used to access project resources
    private final ResourcePatternResolver resourcePatternResolver;



    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }
    public List<Document> loadMarkdowns(){
        List< Document> allDocuments = new ArrayList<>();
        try {
            // Get all md files in the project, directory is under the current project's document folder
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()// Create configuration
                        .withHorizontalRuleCreateDocument(true)// Add horizontal rule
                        .withIncludeCodeBlock(false)// Add code block
                        .withIncludeBlockquote(false)// Add blockquote
                        .withAdditionalMetadata("filename", fileName)// Add metadata, key-value pair where key is filename, value is file name
                        .withAdditionalMetadata("status",fileName.substring(fileName.length()-6,fileName.length()-4))
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                List<Document> docs = reader.get();
                // Add tenantId metadata to all documents (default "default", used for data isolation in multi-tenant scenarios)
                for (Document doc : docs) {
                    doc.getMetadata().putIfAbsent("tenantId", "default");
                }
                allDocuments.addAll(docs);
            }
        } catch (IOException e) {
            log.error("Failed to load Markdown documents", e);
        }
        return allDocuments;
    }


}
