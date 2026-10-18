package dev.example.productai.llm;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次带 tools 的 LLM 调用结果
 *
 * @author wengzewei
 */
@Data
@Builder
public class ToolChatResult {
    /** 文本部分（决策轮通常为空） */
    private String content;
    /** 模型请求调用的工具；空 = 模型不再需要工具 */
    private List<ToolCall> toolCalls;
    /** finish_reason 是否为 tool_calls */
    private boolean finishByToolCalls;
    private Integer promptTokens;
    private Integer completionTokens;
}
