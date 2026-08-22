package cn.lwx.lwxaiagent.admin;

import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Prompt 版本管理（08 §2.4）：启动时检测提示词变化，自动递增版本号。
 * 版本号写入 message.prompt_version 归因每条消息使用的 Prompt 版本。
 */
@Slf4j
@Component
public class PromptVersionService {

    private final JdbcTemplate jdbcTemplate;
    private volatile String currentVersion = "v1";

    /** Prompt 类型常量 */
    private static final String TYPE_CHAT = "chat";
    private static final String TYPE_AGENT = "agent";

    public PromptVersionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void init() {
        // 检测 chat prompt 版本
        currentVersion = detectOrCreateVersion(TYPE_CHAT, ChatExecutor.SYSTEM_PROMPT);
        log.info("Prompt version initialized: {} (type=chat)", currentVersion);
    }

    /**
     * 检测提示词是否已存入版本表，若不存在则创建新版本。
     * @return 当前版本号（如 "v1", "v2"）
     */
    public String detectOrCreateVersion(String type, String promptContent) {
        try {
            // 检查是否已有该内容的记录
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM prompt_version WHERE type = ? AND content = ?",
                    Integer.class, type, promptContent);
            if (count != null && count > 0) {
                // 已有记录，返回当前版本号
                return jdbcTemplate.queryForObject(
                        "SELECT version FROM prompt_version WHERE type = ? ORDER BY id DESC LIMIT 1",
                        String.class, type);
            }
        } catch (Exception e) {
            log.warn("Prompt version check failed (table may not exist yet): {}", e.getMessage());
            return "v1";
        }

        // 提示词内容已变更，创建新版本
        try {
            // 获取当前最大版本号
            String maxVer = jdbcTemplate.queryForObject(
                    "SELECT MAX(version) FROM prompt_version WHERE type = ?",
                    String.class, type);
            int nextNum = 1;
            if (maxVer != null && maxVer.startsWith("v")) {
                nextNum = Integer.parseInt(maxVer.substring(1)) + 1;
            }
            String newVersion = "v" + nextNum;
            jdbcTemplate.update(
                    "INSERT INTO prompt_version (version, type, content, created_at) VALUES (?, ?, ?, NOW())",
                    newVersion, type, promptContent);
            log.info("New prompt version created: {} (type={})", newVersion, type);
            return newVersion;
        } catch (Exception e) {
            log.warn("Failed to create prompt version: {}", e.getMessage());
            return "v1";
        }
    }

    /** 当前 Prompt 版本号 */
    public String getCurrentVersion() {
        return currentVersion;
    }

    /** 获取指定版本的内容 */
    public String getContent(String version, String type) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT content FROM prompt_version WHERE version = ? AND type = ?",
                    String.class, version, type);
        } catch (Exception e) {
            return null;
        }
    }
}