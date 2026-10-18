package dev.example.productai.llm;

/**
 * 一次 function calling 的工具调用（模型侧输出）
 *
 * @author wengzewei
 */
public record ToolCall(String id, String name, String arguments) {
}
