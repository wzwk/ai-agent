package dev.example.productai.agent;

import dev.example.productai.model.ChatResponse;

/** SSE 实时输出钩子；独立于具体执行器，供轻量循环和状态图共同使用。 */
public record AgentStreamHooks(java.util.function.Consumer<ChatResponse.Step> onStep,
                               java.util.function.Consumer<String> onDelta) {
    public static AgentStreamHooks none() {
        return null;
    }
}
