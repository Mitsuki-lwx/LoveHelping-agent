package cn.lwx.lwxaiagent.infrastructure.ai;

import java.util.List;

/**
 * 视觉模型统一抽象（ADR-17）。
 * <p>
 * 屏蔽底层协议差异（当前 OpenAI 兼容 /v1/chat/completions；未来 Anthropic /v1/messages 等），
 * 业务层（ChatService）只依赖本接口，新增供应商只需新实现。
 * </p>
 * <p>对话层不重复抽象：Spring AI 的 {@code ChatModel} + LlmGateway 已承担统一角色
 * （Anthropic/OpenAI/DeepSeek 均为 ChatModel 实现）。视觉因外部端点 Media 编码不兼容
 * 而手写实现，故单独抽象。</p>
 */
public interface VisionPort {

    /**
     * 视觉对话：文本 + 多张图片 → 模型回复全文。
     *
     * @param prompt 用户文本（含 system 指令，调用方组装）
     * @param images 图片原始字节（≤4 张，调用方已校验）
     * @param mime   图片 MIME（image/png 等，所有图同类型）
     * @return 模型回复全文
     */
    String chat(String prompt, List<byte[]> images, String mime);
}
