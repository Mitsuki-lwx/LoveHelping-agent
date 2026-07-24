package cn.lwx.lwxaiagent.agent.model;

/**
 * @description: Defines the Agent state enum, representing different states of an Agent
 *
 */
public enum AgentState {

    // Define Agent state properties
    /**
     * IDLE: Idle state, indicates the Agent currently has no task executing and can accept new tasks.
      RUNNING: Running state, indicates the Agent is executing a task, possibly processing input, calling tools, or generating output.
      FINISHED: Finished state, indicates the Agent has successfully completed the current task and can return results or wait for new instructions.
      ERROR: Error state, indicates the Agent encountered a problem during execution, may need error handling or retry.
     */
    IDIE,

    RUNNING,

    FINISHED,

    ERROR


}
