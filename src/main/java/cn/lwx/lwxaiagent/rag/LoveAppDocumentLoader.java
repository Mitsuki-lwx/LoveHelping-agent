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
@Component
@Slf4j
public class LoveAppDocumentLoader {
    //注入资源解析器，这个可以获取到项目中的资源
    private final ResourcePatternResolver resourcePatternResolver;



    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }
    public List<Document> loadMarkdowns(){
        List< Document> allDocuments = new ArrayList<>();
        try {
            // 获取项目下的所有md文件，目录是在当前项目下的document目录下
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()//创建配置
                        .withHorizontalRuleCreateDocument(true)//添加水平分割线
                        .withIncludeCodeBlock(false)//添加代码块
                        .withIncludeBlockquote(false)//添加引号
                        .withAdditionalMetadata("filename", fileName)//添加元数据，元数据是一个键值对，键是filename，值是文件名
                        .withAdditionalMetadata("status",fileName.substring(fileName.length()-6,fileName.length()-4))
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);//创建MarkdownDocumentReader对象
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }


}
