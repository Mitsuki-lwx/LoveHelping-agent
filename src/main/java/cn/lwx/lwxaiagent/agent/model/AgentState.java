package cn.lwx.lwxaiagent.agent.model;

/**
 * @description: 定义Agent状态枚举类，表示Agent的不同状态
 *
 */
public enum AgentState {

    //定义Agent状态属性
    /**
     * IDIE：空闲状态，表示Agent当前没有任务在执行，可以接受新的任务。
      RUNNING：运行状态，表示Agent正在执行任务，可能正在处理输入、调用工具或生成输出。
      FINISHED：完成状态，表示Agent已经成功完成了当前任务，可以返回结果或等待新的指令。
      ERROR：错误状态，表示Agent在执行过程中遇到了问题，可能需要进行错误处理或重新尝试。
     */
    IDIE,

    RUNNING,

    FINISHED,

    ERROR


}
