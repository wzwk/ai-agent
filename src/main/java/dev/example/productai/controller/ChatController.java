package dev.example.productai.controller;

import dev.example.productai.agent.AskContext;
import dev.example.productai.agent.ProductAgent;
import dev.example.productai.config.AgentProperties;
import dev.example.productai.config.LlmProperties;
import dev.example.productai.llm.LlmCancelledException;
import dev.example.productai.llm.LlmCancellation;
import dev.example.productai.llm.LlmRuntime;
import dev.example.productai.model.ChatResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 暴露给前端 / Postman 测试的 REST 接口
 * PoC 阶段先用这个，后续接企微回调后，企微回调也走 ProductAgent
 
 * @author wengzewei*/
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ProductAgent agent;
    private final LlmProperties llmProps;
    private final AgentProperties agentProps;

    /** 从 markdown answer 里抽取 ```mermaid ... ``` 代码块的内容（不含围栏） */
    private static final Pattern MERMAID_BLOCK =
            Pattern.compile("```mermaid\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);

    @Data
    public static class ChatRequest {
        private String question;
        /** 回答模式：user（默认，口语化）/ dev（开发者，含 mermaid 流程图） */
        private String mode;
        /** 会话 ID；为空时服务端生成。多轮对话需带回 */
        private String sessionId;
        /** 本次请求的唯一 ID；前端停止回答时用它调用 /api/chat/cancel 中断后端生成 */
        private String requestId;
        /** 访客自带 Key 的服务商名：deepseek / zhipu（须在 application.yml providers 里已配置模板） */
        private String llmProvider;
        /** 可选：覆盖该服务商的默认模型，如 deepseek-chat / glm-4-flash */
        private String llmModel;
        /** 访客自己的 API Key；只在本次请求中使用，不落库不打日志 */
        private String llmKey;
        /** ===== 接口分析模式字段 ===== */
        /** 指定检索的项目名；非空时跳过 LLM 路由 */
        private List<String> projects;
        /** 接口路径，如 /api/v3/commodity/multiPriceSkuVersionSave */
        private String apiPath;
        /** 分析目标：梳理逻辑 / 排查报错等 */
        private String intent;
        /** 是否生成 mermaid 流程图（dev 模式有效；流程图较耗 token） */
        private Boolean flowchart;
        /** 引擎指定：agent / workflow / legacy / trace；缺省跟随服务端配置。 */
        private String engine;
    }

    /** 由请求里的自带 Key 字段构建运行时覆盖；未填 Key 返回 null（走共享配置） */
    private LlmRuntime buildRuntime(ChatRequest req) {
        if (req.getLlmKey() == null || req.getLlmKey().isBlank()) {
            return null;
        }
        String prov = req.getLlmProvider() == null || req.getLlmProvider().isBlank()
                ? "deepseek" : req.getLlmProvider().trim().toLowerCase();
        LlmProperties.Provider tpl = llmProps.getProviders() == null ? null : llmProps.getProviders().get(prov);
        if (tpl == null) {
            throw new IllegalArgumentException("不支持的服务商: " + prov);
        }
        String model = req.getLlmModel() != null && !req.getLlmModel().isBlank()
                ? req.getLlmModel().replaceAll("[^A-Za-z0-9._\\-]", "") : tpl.getModel();
        return LlmRuntime.builder()
                .baseUrl(tpl.getBaseUrl())
                .apiKey(req.getLlmKey().trim())
                .model(model)
                .build();
    }

    /** 主问答接口，返回完整 JSON 响应 */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return ResponseEntity.ok(ChatResponse.builder()
                    .answer("问题不能为空")
                    .confidence("high")
                    .needHuman(false)
                    .elapsedMs(0)
                    .build());
        }
        String mode = (req.getMode() == null || req.getMode().isBlank())
                ? "user"
                : req.getMode().trim().toLowerCase();
        LlmRuntime rt;
        try {
            rt = buildRuntime(req);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ChatResponse.builder()
                    .answer(e.getMessage())
                    .confidence("low")
                    .needHuman(false)
                    .elapsedMs(0)
                    .build());
        }
        ChatResponse resp = askWithCancellation(req, AskContext.builder()
                .question(req.getQuestion())
                .mode(mode)
                .sessionId(req.getSessionId())
                .rt(rt)
                .specifiedProjects(req.getProjects())
                .apiPath(req.getApiPath())
                .intent(req.getIntent())
                .flowchart(req.getFlowchart())
                .engine(req.getEngine())
                .build());
        return ResponseEntity.ok(resp);
    }

    /** 带 requestId 注册的执行：前端可通过 /api/chat/cancel 中断后端 LLM 生成 */
    private ChatResponse askWithCancellation(ChatRequest req, AskContext ctx) {
        long startedAt = System.currentTimeMillis();
        String requestId = req.getRequestId();
        boolean cancellable = requestId != null && !requestId.isBlank();
        if (cancellable) {
            LlmCancellation.bind(LlmCancellation.register(requestId));
        }
        try {
            return agent.ask(ctx);
        } catch (LlmCancelledException e) {
            log.info("请求已被用户取消，requestId={}", requestId);
            return ChatResponse.builder()
                    .answer("（已取消本次回答）")
                    .sessionId(ctx.getSessionId())
                    .mode(ctx.getMode())
                    .confidence("low")
                    .needHuman(false)
                    .elapsedMs(System.currentTimeMillis() - startedAt)
                    .engine("cancelled").completionStatus("partial")
                    .incompleteReason("用户主动取消")
                    .performance(performance("cancelled", System.currentTimeMillis() - startedAt))
                    .build();
        } finally {
            LlmCancellation.unbind();
            if (cancellable) {
                LlmCancellation.unregister(requestId);
            }
        }
    }

    /** 前端停止回答：中断对应 requestId 的后端生成（LLM 流式读取立即断开） */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam("requestId") String requestId) {
        boolean ok = requestId != null && !requestId.isBlank() && LlmCancellation.cancel(requestId);
        return Map.of("ok", ok);
    }

    // ==================== SSE 实时流（v0.10）====================
    // 事件协议：event: step  data:{Step JSON}   —— 工具调用/结果/强制成文实时推
    //          event: delta data:"文本增量"     —— 成文轮打字机
    //          event: done  data:{ChatResponse} —— 终态（前端用它做最终渲染，含 mermaid/meta）
    private final com.fasterxml.jackson.databind.ObjectMapper sseMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @PostMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> chatStream(
            @RequestBody ChatRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            StreamingResponseBody empty = out -> writeSse(out, "done", sseMapper.writeValueAsString(
                    ChatResponse.builder().answer("问题不能为空").confidence("low").elapsedMs(0).build()));
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(empty);
        }
        String mode = (req.getMode() == null || req.getMode().isBlank())
                ? "user" : req.getMode().trim().toLowerCase();
        LlmRuntime rt;
        try {
            rt = buildRuntime(req);
        } catch (IllegalArgumentException e) {
            StreamingResponseBody err = out -> writeSse(out, "done", sseMapper.writeValueAsString(
                    ChatResponse.builder().answer(e.getMessage()).confidence("low").elapsedMs(0).build()));
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(err);
        }
        AskContext ctx = AskContext.builder()
                .question(req.getQuestion())
                .mode(mode)
                .sessionId(req.getSessionId())
                .rt(rt)
                .specifiedProjects(req.getProjects())
                .apiPath(req.getApiPath())
                .intent(req.getIntent())
                .flowchart(req.getFlowchart())
                .engine(req.getEngine())
                .build();

        String requestId = req.getRequestId();
        boolean cancellable = requestId != null && !requestId.isBlank();

        StreamingResponseBody body = out -> {
            long startedAt = System.currentTimeMillis();
            if (cancellable) LlmCancellation.bind(LlmCancellation.register(requestId));
            try {
                ChatResponse resp = agent.askStream(ctx, new dev.example.productai.agent.AgentExecutor.StreamHooks(
                        step -> writeSseQuietly(out, "step", toJsonQuietly(step)),
                        delta -> writeSseQuietly(out, "delta", toJsonQuietly(delta))
                ));
                writeSse(out, "done", sseMapper.writeValueAsString(resp));
            } catch (LlmCancelledException e) {
                log.info("流式请求已取消，requestId={}", requestId);
                writeSseQuietly(out, "done", toJsonQuietly(ChatResponse.builder()
                        .answer("（已取消本次回答）").sessionId(ctx.getSessionId()).mode(mode)
                        .confidence("low").needHuman(false)
                        .elapsedMs(System.currentTimeMillis() - startedAt)
                        .engine("cancelled").completionStatus("partial")
                        .incompleteReason("用户主动取消")
                        .performance(performance("cancelled", System.currentTimeMillis() - startedAt)).build()));
            } catch (Exception e) {
                log.error("流式回答失败", e);
                writeSseQuietly(out, "done", toJsonQuietly(ChatResponse.builder()
                        .answer("回答失败：" + e.getMessage()).confidence("low")
                        .elapsedMs(System.currentTimeMillis() - startedAt)
                        .engine("error").completionStatus("partial")
                        .incompleteReason(e.getClass().getSimpleName())
                        .performance(performance("error", System.currentTimeMillis() - startedAt)).build()));
            } finally {
                LlmCancellation.unbind();
                if (cancellable) LlmCancellation.unregister(requestId);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    private static ChatResponse.Performance performance(String route, long elapsedMs) {
        return ChatResponse.Performance.builder().route(route).totalMs(elapsedMs).build();
    }

    private void writeSse(java.io.OutputStream out, String event, String data) {
        writeSseQuietly(out, event, data);
    }

    /** SSE 帧：event: xxx\ndata: ...\n\n（data 必须单行，JSON 序列化天然满足） */
    private void writeSseQuietly(java.io.OutputStream out, String event, String data) {
        try {
            out.write(("event: " + event + "\ndata: " + data + "\n\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
            // 客户端断开（停止回答）时写入失败属预期，吞掉
        }
    }

    private String toJsonQuietly(Object o) {
        try {
            return sseMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    /**
     * 调试端点：返回纯文本的 mermaid 代码块（不含 ```mermaid 围栏），Content-Type: text/plain。
     * 用途：curl 一调就能直接复制粘贴到 https://mermaid.live 或飞书 markdown，
     *       不用自己解析 JSON。
     *
     * 默认走 dev 模式（user 模式不会输出 mermaid）。
     * 如果回答里没找到 mermaid 块，返回提示 + 完整 answer 文本，便于排查。
     *
     * 多轮对话支持：因为 body 是纯文本不能塞 JSON 元数据，sessionId 通过响应头返回：
     *   - X-Session-Id:  会话 ID，下次请求带回 body.sessionId 即可续轮
     *   - X-Confidence:   本轮置信度（high/medium/low）
     *   - X-Mode:         本轮使用的模式
     */
    @PostMapping(value = "/diagram", produces = MediaType.TEXT_PLAIN_VALUE + "; charset=UTF-8")
    public ResponseEntity<String> diagram(@RequestBody ChatRequest req) {
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            return ResponseEntity.ok("(问题不能为空)");
        }
        String mode = (req.getMode() == null || req.getMode().isBlank())
                ? "dev"
                : req.getMode().trim().toLowerCase();
        LlmRuntime rt;
        try {
            rt = buildRuntime(req);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok("(配置错误) " + e.getMessage());
        }
        ChatResponse resp = agent.ask(req.getQuestion(), mode, req.getSessionId(), rt);
        String mermaid = extractMermaidBlock(resp.getAnswer());
        String body = (mermaid != null)
                ? mermaid
                : "(未在回答里找到 mermaid 代码块，可能是 user 模式或 LLM 漏输出。以下是完整回答)\n\n"
                        + resp.getAnswer();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Session-Id", resp.getSessionId());
        headers.set("X-Confidence", resp.getConfidence());
        headers.set("X-Mode", resp.getMode());
        headers.set("X-Source-Files", String.join(",", resp.getSourceFiles()));

        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /** 健康检查 + 配置查看 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "provider", "see application.yml -> llm.active",
                "agentEnabled", agentProps.isEnabled()
        );
    }

    /** 从 markdown 文本里抽取第一个 ```mermaid 块的内容（不含围栏）；没找到返回 null */
    private String extractMermaidBlock(String answer) {
        if (answer == null || answer.isEmpty()) return null;
        Matcher m = MERMAID_BLOCK.matcher(answer);
        return m.find() ? m.group(1).trim() : null;
    }
}
