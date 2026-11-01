package dev.example.productai.agent;

/** 工具执行结果：content 回喂模型，summary 用于可观察时间线。 */
public record ToolExecResult(boolean ok, String content, String summary) {
}
