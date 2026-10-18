package dev.example.productai.llm;

import java.util.List;

/**
 * LLM 客户端抽象，底层可切换 DeepSeek / 智谱 / 其他
 * 走 OpenAI 兼容协议（DeepSeek 和智谱都支持）
 
 * @author wengzewei*/
public interface LlmClient {

    /**
     * 简单问答
     *
     * @param systemPrompt 系统提示词（可为 null）
     * @param userMessage  用户消息
     * @return 模型回复的文本
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * 带运行时覆盖的问答（访客自带 Key 场景）
     *
     * @param rt 覆盖参数；为空或无 key 时回退到全局配置
     */
    default String chat(String systemPrompt, String userMessage, LlmRuntime rt) {
        return chat(systemPrompt, userMessage);
    }

    /**
     * 流式问答（SSE 实时透传用）：每个增量片段回调 onDelta，返回完整文本。
     * 默认实现退化为非流式（onDelta 不触发）。
     */
    default String chatStream(String systemPrompt, String userMessage, LlmRuntime rt,
                              java.util.function.Consumer<String> onDelta) {
        return chat(systemPrompt, userMessage, rt);
    }

    /**
     * 带历史对话的问答
     *
     * @param messages 消息列表（system + 多轮 user/assistant）
     * @return 模型回复的文本
     */
    String chat(List<ChatMessage> messages);

    /**
     * 带 tools 的智能体决策调用（function calling）。
     * 返回模型文本或其请求的工具调用列表；rt 为空走全局配置。
     */
    default ToolChatResult chatWithTools(List<ToolChatMessage> messages,
                                         List<java.util.Map<String, Object>> tools,
                                         LlmRuntime rt) {
        throw new UnsupportedOperationException("当前 LlmClient 不支持 function calling");
    }

    /**
     * 当前使用的 provider 名（deepseek / zhipu）
     */
    String currentProvider();

    /**
     * 一条消息
     */
    record ChatMessage(String role, String content) {
        public static ChatMessage system(String text) {
            return new ChatMessage("system", text);
        }
        public static ChatMessage user(String text) {
            return new ChatMessage("user", text);
        }
        public static ChatMessage assistant(String text) {
            return new ChatMessage("assistant", text);
        }
    }
}
