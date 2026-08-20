package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.AgentTask;
import cn.lwx.lwxaiagent.infrastructure.ai.AgentLoopExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>聊天服务（编排层重构后，精简版）</h1>
 *
 * <p>原 ChatService 包含 7 个聊天方法 + Agent 方法，职责繁重。
 * 重构后（编排层拆分）：</p>
 * <ul>
 *   <li><b>聊天路由</b>→ {@link ChatEntry}（统一入口 + CapabilityRouter + ChatExecutor）</li>
 *   <li><b>Agent 任务状态</b>→ 本类仅保留 getAgentTask / stopAgent（管理操作）</li>
 *   <li><b>旧 6 个聊天方法</b>→ 已迁移至 ChatEntry/ChatExecutor，全部删除</li>
 * </ul>
 *
 * @see ChatEntry 聊天统一入口
 * @see ChatExecutor 浅层执行器
 * @see AgentLoopExecutor 深层执行器（ReactAgent）
 */
@Slf4j
@Service
public class ChatService {

    /** Agent 任务服务（状态查询/停止，管理操作） */
    private final cn.lwx.lwxaiagent.service.AgentTaskService agentTaskService;

    /** Agent 执行器（停止操作需要取消订阅） */
    private final AgentLoopExecutor agentLoopExecutor;

    /**
     * 活跃的 Agent SSE 发射器映射（sessionId → emitter）。
     * AgentEntry.chat() 写入，stopAgent 清除。
     */
    private final ConcurrentHashMap<String, SseEmitter> activeSessions = new ConcurrentHashMap<>();

    public ChatService(AgentTaskService agentTaskService, AgentLoopExecutor agentLoopExecutor) {
        this.agentTaskService = agentTaskService;
        this.agentLoopExecutor = agentLoopExecutor;
    }

    /**
     * 查询 Agent 任务状态（管理操作，直接由 AiController 调用）。
     */
    public AgentTask getAgentTask(Long taskId) {
        return agentTaskService.get(taskId);
    }

    /**
     * 停止运行中的 Agent 会话（管理操作）。
     * 取消 ReactAgent 订阅；任务状态由心跳补偿扫描兜底。
     */
    public String stopAgent(String sessionId) {
        SseEmitter emitter = activeSessions.remove(sessionId);
        if (emitter != null) {
            agentLoopExecutor.stop(sessionId);
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            return "stopped";
        }
        return "no_active_session";
    }

    /**
     * 注册活跃 SSE（由 ChatEntry 调用）。
     */
    public void registerSession(String sessionId, SseEmitter emitter) {
        activeSessions.put(sessionId, emitter);
    }

    /**
     * 从活跃映射中移除 SSE（emitter 生命周期结束时调用）。
     */
    public void unregisterSession(String sessionId) {
        activeSessions.remove(sessionId);
    }
}
