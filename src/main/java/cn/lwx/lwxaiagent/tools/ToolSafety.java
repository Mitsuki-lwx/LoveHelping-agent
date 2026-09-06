package cn.lwx.lwxaiagent.tools;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Agent 工具输入安全约束（2026-09-06 工具侧安全抽查落地）。
 * <p>背景：PDF/下载工具此前对 LLM 传入的 fileName 直拼路径（可穿越写盘）、URL 直连
 * （可 SSRF 内网）——工具输入面完全信任模型输出，prompt 注入可诱导任意写文件/内网探测。
 * 提供两个纯函数约束：文件名清洗 + HTTP(S) URL 校验（协议白名单 + 内网段拦截）。
 */
public final class ToolSafety {

    private ToolSafety() {
    }

    /** 文件名清洗：剥除路径分隔符、上级引用与控制字符——只保留安全文件名字符集 */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        // 先剥掉任意路径成分（Windows/Unix 分隔、驱动盘、.. 上级引用）
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replace("..", "").replace(":", "").replace("?", "").replace("*", "");
        // 控制字符与空白折叠
        cleaned = cleaned.replaceAll("[\\p{Cntrl}\\s]+", "_");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    /** 内网段前缀（SSRF 基础拦截：回环/链路本地/私有段） */
    private static final String[] PRIVATE_PREFIXES = {
            "127.", "10.", "192.168.", "169.254.", "0.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.", "172.27.",
            "172.28.", "172.29.", "172.30.", "172.31."
    };

    private static final Pattern PRIVATE_HOST = Pattern.compile(
            "(?i)^(localhost|\\.local|.*\\.internal|.*\\.localdomain)$");

    /**
     * 校验图片/资源下载 URL：仅允许 http/https + 非内网 host。
     *
     * @return 校验失败时返回原因；通过返回 null
     */
    public static String validateHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return "url 为空";
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return "仅支持 http/https 协议";
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "URL 缺少 host";
            }
            String lower = host.toLowerCase();
            if (PRIVATE_HOST.matcher(lower).matches()) {
                return "禁止访问内网地址: " + host;
            }
            // IP 形式内网段
            for (String p : PRIVATE_PREFIXES) {
                if (lower.startsWith(p)) {
                    return "禁止访问内网地址: " + host;
                }
            }
            // IPv6 回环/本地
            if ("::1".equals(lower) || lower.startsWith("fc") || lower.startsWith("fd")
                    || lower.startsWith("fe80")) {
                return "禁止访问内网地址: " + host;
            }
            return null;
        } catch (Exception e) {
            return "URL 非法: " + url;
        }
    }
}
