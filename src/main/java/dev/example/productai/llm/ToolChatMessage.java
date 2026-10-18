package dev.example.productai.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 智能体循环用的消息结构（比 model.ChatMessage 多 tool_calls / tool_call_id 字段）。
 * role: system / user / assistant / tool
 *
 * @author wengzewei
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolChatMessage {
    private String role;
    private String content;
    /** assistant 消息携带的 tool_calls（模型决策结果原样回填） */
    private List<ToolCall> toolCalls;
    /** role=tool 时对应的调用 id */
    private String toolCallId;

    public static ToolChatMessage system(String text) {
        return ToolChatMessage.builder().role("system").content(text).build();
    }

    public static ToolChatMessage user(String text) {
        return ToolChatMessage.builder().role("user").content(text).build();
    }

    public static ToolChatMessage assistant(String text) {
        return ToolChatMessage.builder().role("assistant").content(text == null ? "" : text).build();
    }

    public static ToolChatMessage tool(String toolCallId, String content) {
        return ToolChatMessage.builder().role("tool").toolCallId(toolCallId).content(content).build();
    }
}
