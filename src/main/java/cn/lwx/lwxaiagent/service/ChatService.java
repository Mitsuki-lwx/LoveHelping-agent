package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.AgentTask;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <h1>聊天服务（编排层重构后，精简版）</h1>
 *
 * <p>入口交叉关注点与路由均由 {@code ChatEntry} + 业务编排图（ADR-19）承担；
 * 本类仅保留管理操作：Agent 任务状态查询与停止（停止经 GraphRunner 取消活跃执行）。</p>
 */
@Slf4j
@Service
public class ChatService {

    /** Agent 任务服务（状态查询/停止，管理操作） */
    private final AgentTaskService agentTaskService;

    /** 业务编排图执行门面（停止 = 取消活跃异步执行） */
    private final GraphRunner graphRunner;

    public ChatService(AgentTaskService agentTaskService, GraphRunner graphRunner) {
        this.agentTaskService = agentTaskService;
        this.graphRunner = graphRunner;
    }

    /**
     * 查询 Agent 任务状态（管理操作，直接由 AiController 调用）。
     */
    public AgentTask getAgentTask(Long taskId) {
        return agentTaskService.get(taskId);
    }

    /**
     * 停止运行中的会话（管理操作）。
     * 取消图执行；任务状态由心跳补偿扫描兜底。
     */
    public String stopAgent(String sessionId) {
        graphRunner.stop(sessionId);
        return "stopped";
    }
}