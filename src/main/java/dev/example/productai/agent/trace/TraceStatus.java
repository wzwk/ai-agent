package dev.example.productai.agent.trace;

/** 调用链调查任务状态。 */
public enum TraceStatus {
    RUNNING,
    PARTIAL,
    COMPLETE,
    FAILED
}
