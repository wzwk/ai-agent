package dev.example.productai.agent;

import dev.example.productai.llm.ToolCall;
import dev.example.productai.llm.ToolChatMessage;
import dev.example.productai.model.ConversationSession;

import java.util.List;
import java.util.Map;

/**
 * Agent 流程控制层所需的最小工具运行时端口。
 *
 * <p>TraceGraphExecutor 只依赖这组能力，不再直接依赖旧 AgentExecutor。后续可以把
 * 工具实现迁移到独立组件，而无需再次改动状态图流程。</p>
 */
public interface AgentToolRuntime {
    void checkCancelled();

    ToolExecResult executeTool(ToolCall call, Map<String, Boolean> availability,
                               AgentHarvest harvest, Map<String, Integer> failStreak);

    void compressIfNeeded(List<ToolChatMessage> messages);

    Map<String, Boolean> computeAvailability();

    String buildExploreSystemPrompt(String mode, Map<String, Boolean> availability,
                                    int budget, boolean deepTrace, int graceTurns);

    String buildFinalSystemPrompt(String mode, List<String> exploredProjects,
                                  boolean flowchart, boolean concise);

    List<String> distillMaterials(AgentHarvest harvest);

    String renderHistory(ConversationSession session);

    List<Map<String, Object>> toolSchemas();

    String toRelative(String filePath);

    String[] parseConfidence(String response);
}
