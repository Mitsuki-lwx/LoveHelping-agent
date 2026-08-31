package cn.lwx.lwxaiagent.rag.rerank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 重排配置（ADR-15 阶段 4，默认关闭灰度）。
 */
@Component
@ConfigurationProperties(prefix = "app.rag.rerank")
public class RerankProperties {

    /** 总开关（默认关，开启后每条 RAG 多一次 LLM 调用） */
    private boolean enabled = false;
    /** 粗召回窗口（检索器 topK，子块级） */
    private int topN = 50;
    /** 重排后输出条数（注入生成） */
    private int topK = 5;
    /** 重排模式：llm = 主线 LLM 打分；off = 关闭 */
    private String mode = "llm";
    /** 每个候选进 LLM 前截断的字符数（控 token） */
    private int maxCandidateChars = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getMaxCandidateChars() {
        return maxCandidateChars;
    }

    public void setMaxCandidateChars(int maxCandidateChars) {
        this.maxCandidateChars = maxCandidateChars;
    }
}