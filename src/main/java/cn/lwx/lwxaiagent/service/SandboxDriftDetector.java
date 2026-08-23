package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.SandboxSession;
import cn.lwx.lwxaiagent.mapper.SandboxSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 沙盘人设漂移检测（ADR-9 层 2）。
 * 每轮沙盘回复后异步检测是否偏离 persona 设定。
 */
@Slf4j
@Component
public class SandboxDriftDetector {

    private final SandboxSessionMapper sessionMapper;

    public SandboxDriftDetector(SandboxSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /**
     * 通知一次漂移事件（调用方判定已漂移后调用）。
     * 递增 drift_count，>=3 时标记 needs_user_confirm=1。
     */
    public void onDrift(Long sessionId) {
        SandboxSession session = sessionMapper.selectById(sessionId);
        if (session == null) return;
        session.setDriftCount(session.getDriftCount() + 1);
        if (session.getDriftCount() >= 3) {
            session.setNeedsUserConfirm(1);
            log.info("Sandbox {} drift_count reached 3, marking needs_user_confirm", sessionId);
        }
        sessionMapper.updateById(session);
    }

    /**
     * 漂移重置（用户确认后调用）。
     */
    public void resetDrift(Long sessionId) {
        SandboxSession session = sessionMapper.selectById(sessionId);
        if (session == null) return;
        session.setDriftCount(0);
        session.setNeedsUserConfirm(0);
        sessionMapper.updateById(session);
    }
}
