package dev.example.productai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.example.productai.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容协议的 LLM 客户端实现
 * 同时支持 DeepSeek（https://api.deepseek.com/v1）和智谱（https://open.bigmodel.cn/api/paas/v4）
 * 通过 LlmProperties.active 切换
 
 * @author wengzewei*/
@Slf4j
@Component
public class OpenAiCompatibleClient implements LlmClient {

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final RestClient answerClient;
    private final RestClient decisionClient;

    public OpenAiCompatibleClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.answerClient = buildClient(ANSWER_READ_TIMEOUT_MS);
        this.decisionClient = buildClient(DECISION_READ_TIMEOUT_MS);
        log.info("LlmClient 初始化完成，当前 provider={}, model={}",
                props.getActiveProvider().getBaseUrl(),
                props.getActiveProvider().getModel());
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chat(List.of(
                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                ChatMessage.user(userMessage)
        ));
    }

    @Override
    public String chat(String systemPrompt, String userMessage, LlmRuntime rt) {
        if (rt == null || !rt.hasKey()) {
            return chat(systemPrompt, userMessage);
        }
        // 访客自带 Key：端点/模型优先用覆盖值，缺失时回退到全局 provider 模板
        LlmProperties.Provider tpl = props.getActiveProvider();
        String baseUrl = (rt.getBaseUrl() != null && !rt.getBaseUrl().isBlank())
                ? rt.getBaseUrl() : tpl.getBaseUrl();
        String model = (rt.getModel() != null && !rt.getModel().isBlank())
                ? rt.getModel() : tpl.getModel();
        List<ChatMessage> msgs = List.of(
                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                ChatMessage.user(userMessage)
        );
        return postChatCompletions(baseUrl, rt.getApiKey(), model,
                tpl.getTemperature(), tpl.getMaxTokens(), msgs);
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        LlmProperties.Provider p = props.getActiveProvider();
        return postChatCompletions(p.getBaseUrl(), p.getApiKey(), p.getModel(),
                p.getTemperature(), p.getMaxTokens(), messages);
    }

    private String postChatCompletions(String baseUrl, String apiKey, String model,
                                       Double temperature, Integer maxTokens, List<ChatMessage> messages) {
        AtomicBoolean cancel = LlmCancellation.token();
        if (cancel != null && cancel.get()) {
            throw new LlmCancelledException();
        }
        // 绑定了取消标记的请求走流式：逐块检查取消标记，可随时断开，token 停止消耗
        return cancel != null
                ? postChatCompletionsStream(baseUrl, apiKey, model, temperature, maxTokens, messages, cancel)
                : postChatCompletionsBlock(baseUrl, apiKey, model, temperature, maxTokens, messages);
    }

    /** 原阻塞式调用（不可取消场景：企微回调、diagram 调试端点等） */
    private String postChatCompletionsBlock(String baseUrl, String apiKey, String model,
                                            Double temperature, Integer maxTokens, List<ChatMessage> messages) {
        String url = baseUrl + "/chat/completions";

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature == null ? 0.3 : temperature);
        body.put("max_tokens", maxTokens == null ? 8192 : maxTokens);
        body.put("stream", false);

        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode one = msgs.addObject();
            one.put("role", m.role());
            one.put("content", m.content());
        }

        long start = System.currentTimeMillis();
        try {
            String response = answerClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            String content = extractContent(response);
            long cost = System.currentTimeMillis() - start;
            log.debug("LLM 调用完成，耗时={}ms，回复长度={}", cost, content == null ? 0 : content.length());
            return content;
        } catch (Exception e) {
            // 注意：不要把 apiKey 打进日志
            log.error("LLM 调用失败 url={}, model={}", url, model, e);
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /** 流式问答：强制走流式并把增量透传给 onDelta（SSE 实时输出用） */
    @Override
    public String chatStream(String systemPrompt, String userMessage, LlmRuntime rt,
                             java.util.function.Consumer<String> onDelta) {
        LlmProperties.Provider tpl = props.getActiveProvider();
        String baseUrl = tpl.getBaseUrl();
        String apiKey = tpl.getApiKey();
        String model = tpl.getModel();
        if (rt != null && rt.hasKey()) {
            if (rt.getBaseUrl() != null && !rt.getBaseUrl().isBlank()) baseUrl = rt.getBaseUrl();
            if (rt.getModel() != null && !rt.getModel().isBlank()) model = rt.getModel();
            apiKey = rt.getApiKey();
        }
        List<ChatMessage> msgs = List.of(
                ChatMessage.system(systemPrompt == null ? "" : systemPrompt),
                ChatMessage.user(userMessage)
        );
        AtomicBoolean cancel = LlmCancellation.token();
        if (cancel == null) cancel = new AtomicBoolean(false);
        return postChatCompletionsStream(baseUrl, apiKey, model, tpl.getTemperature(), tpl.getMaxTokens(),
                msgs, cancel, onDelta);
    }

    /** 流式调用：边读边拼装 content，每块之间检查取消标记；取消时关闭连接（上游同步停止生成） */
    private String postChatCompletionsStream(String baseUrl, String apiKey, String model,
                                             Double temperature, Integer maxTokens,
                                             List<ChatMessage> messages, AtomicBoolean cancel) {
        return postChatCompletionsStream(baseUrl, apiKey, model, temperature, maxTokens, messages, cancel, null);
    }

    private String postChatCompletionsStream(String baseUrl, String apiKey, String model,
                                             Double temperature, Integer maxTokens,
                                             List<ChatMessage> messages, AtomicBoolean cancel,
                                             java.util.function.Consumer<String> onDelta) {
        String url = baseUrl + "/chat/completions";

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature == null ? 0.3 : temperature);
        body.put("max_tokens", maxTokens == null ? 8192 : maxTokens);
        body.put("stream", true);

        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode one = msgs.addObject();
            one.put("role", m.role());
            one.put("content", m.content());
        }

        long start = System.currentTimeMillis();
        try {
            String content = answerClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(body.toString())
                    .exchange((req, res) -> {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (cancel.get()) {
                                    throw new LlmCancelledException();
                                }
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String data = line.substring(5).trim();
                                if (data.isEmpty()) {
                                    continue;
                                }
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                try {
                                    JsonNode delta = mapper.readTree(data)
                                            .path("choices").path(0).path("delta").path("content");
                                    if (!delta.isMissingNode() && !delta.isNull()) {
                                        String piece = delta.asText();
                                        sb.append(piece);
                                        if (onDelta != null && !piece.isEmpty()) {
                                            onDelta.accept(piece);   // 实时透传给前端
                                        }
                                    }
                                } catch (Exception parseEx) {
                                    // 单块解析失败不影响整体
                                }
                            }
                        }
                        return sb.toString();
                    });
            long cost = System.currentTimeMillis() - start;
            log.debug("LLM 流式调用完成，耗时={}ms，回复长度={}", cost, content.length());
            return content;
        } catch (LlmCancelledException e) {
            log.info("LLM 流式调用已取消 url={}, model={}", url, model);
            throw e;
        } catch (Exception e) {
            log.error("LLM 调用失败 url={}, model={}", url, model, e);
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String currentProvider() {
        return props.getActive() + " (" + props.getActiveProvider().getModel() + ")";
    }

    // ==================== 智能体 function calling（决策轮，非流式） ====================

    @Override
    public ToolChatResult chatWithTools(List<ToolChatMessage> messages,
                                        List<Map<String, Object>> tools,
                                        LlmRuntime rt) {
        LlmProperties.Provider tpl = props.getActiveProvider();
        String baseUrl = tpl.getBaseUrl();
        String apiKey = tpl.getApiKey();
        String model = tpl.getModel();
        if (rt != null && rt.hasKey()) {
            // BYOK：完全尊重访客自选（决策模型覆盖不生效，避免用了对端没有的模型名）
            if (rt.getBaseUrl() != null && !rt.getBaseUrl().isBlank()) baseUrl = rt.getBaseUrl();
            if (rt.getModel() != null && !rt.getModel().isBlank()) model = rt.getModel();
            apiKey = rt.getApiKey();
        } else if (tpl.getDecisionModel() != null && !tpl.getDecisionModel().isBlank()) {
            // 共享配置：决策轮走快模型（成文轮 chat() 不受影响，仍用主模型）
            model = tpl.getDecisionModel();
        }
        String url = baseUrl + "/chat/completions";

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", tpl.getTemperature() == null ? 0.2 : tpl.getTemperature());
        body.put("max_tokens", 8192);
        body.put("stream", false);

        ArrayNode msgs = body.putArray("messages");
        for (ToolChatMessage m : messages) {
            ObjectNode one = msgs.addObject();
            one.put("role", m.getRole());
            if (m.getContent() != null) one.put("content", m.getContent());
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                ArrayNode tcs = one.putArray("tool_calls");
                for (ToolCall tc : m.getToolCalls()) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id());
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.arguments() == null ? "{}" : tc.arguments());
                }
            }
            if (m.getToolCallId() != null) one.put("tool_call_id", m.getToolCallId());
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode ts = body.putArray("tools");
            for (Map<String, Object> t : tools) {
                ts.add(mapper.valueToTree(t));
            }
        }

        long start = System.currentTimeMillis();
        try {
            String response = decisionClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            ToolChatResult r = parseToolResult(response);
            log.debug("chatWithTools 完成，model={}，耗时={}ms，toolCalls={}，contentLen={}", model,
                    System.currentTimeMillis() - start,
                    r.getToolCalls() == null ? 0 : r.getToolCalls().size(),
                    r.getContent() == null ? 0 : r.getContent().length());
            return r;
        } catch (Exception e) {
            // 不要把 apiKey 打进日志
            log.error("chatWithTools 失败 url={}, model={}", url, model, e);
            throw new RuntimeException("LLM 工具调用失败: " + e.getMessage(), e);
        }
    }

    /** 决策轮 read 超时：模型短输出，120s 足够 */
    private static final int DECISION_READ_TIMEOUT_MS = 120_000;
    /** 成文轮/普通调用 read 超时：与前端 600s 硬超时对齐 */
    /** 单次最终成文超过 5 分钟视为上游异常，返回明确失败，不能无限占住 SSE。 */
    private static final int ANSWER_READ_TIMEOUT_MS = 300_000;

    /** 长期复用 RestClient；底层 HttpURLConnection 使用 JVM keep-alive 复用持久连接。 */
    private static RestClient buildClient(int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    /** 解析 OpenAI 兼容响应里的 content / tool_calls / finish_reason / usage */
    private ToolChatResult parseToolResult(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String finish = choice.path("finish_reason").asText("");

            List<ToolCall> calls = new ArrayList<>();
            JsonNode tcs = message.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    calls.add(new ToolCall(
                            tc.path("id").asText("call_" + UUID.randomUUID().toString().substring(0, 8)),
                            tc.path("function").path("name").asText(""),
                            tc.path("function").path("arguments").asText("{}")));
                }
            }
            JsonNode content = message.path("content");
            JsonNode usage = root.path("usage");
            return ToolChatResult.builder()
                    .content(content.isMissingNode() || content.isNull() ? "" : content.asText())
                    .toolCalls(calls)
                    .finishByToolCalls("tool_calls".equals(finish))
                    .promptTokens(usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt())
                    .completionTokens(usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt())
                    .build();
        } catch (Exception e) {
            log.warn("解析 tool_calls 响应失败: {}", json, e);
            return ToolChatResult.builder().content("").toolCalls(List.of()).build();
        }
    }

    /** 从 OpenAI 兼容响应里抽 content 字段 */
    private String extractContent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                return null;
            }
            JsonNode content = choices.get(0).path("message").path("content");
            return content.isMissingNode() ? null : content.asText();
        } catch (Exception e) {
            log.warn("解析 LLM 响应失败: {}", json, e);
            return null;
        }
    }
}
