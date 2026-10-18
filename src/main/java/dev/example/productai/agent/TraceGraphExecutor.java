package dev.example.productai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.example.productai.agent.trace.TraceCompletenessGate;
import dev.example.productai.agent.trace.TraceEdge;
import dev.example.productai.agent.trace.TraceEvidenceExtractor;
import dev.example.productai.agent.trace.TraceState;
import dev.example.productai.agent.trace.TraceStatus;
import dev.example.productai.agent.trace.TraceStuckDetector;
import dev.example.productai.agent.trace.TraceTerminal;
import dev.example.productai.config.AgentProperties;
import dev.example.productai.llm.LlmClient;
import dev.example.productai.llm.ToolCall;
import dev.example.productai.llm.ToolChatMessage;
import dev.example.productai.llm.ToolChatResult;
import dev.example.productai.model.ChatMessage;
import dev.example.productai.model.ChatResponse;
import dev.example.productai.model.ConversationSession;
import dev.example.productai.repository.TraceCheckpointRepository;
import dev.example.productai.search.JavaSymbolIndex;
import dev.example.productai.search.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向完整调用链的有状态执行器。
 *
 * <p>与旧自由循环不同：模型只负责选择工具和报告从代码中看到的事实，程序负责保存
 * pending edge、阻止重复调用、恢复 checkpoint，并通过 CompletenessGate 决定是否完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceGraphExecutor {

    private static final Set<String> CONTINUATION_WORDS = Set.of(
            "继续", "接着", "继续深挖", "继续查", "往下查", "没闭环", "查到底");
    private static final Pattern STORED_SOURCE_MATERIAL = Pattern.compile(
            "^【(?:read_file|search_in_file)\\s+([^\\s】]+).*?】\\R([\\s\\S]*)$");

    private final LlmClient llm;
    private final AgentExecutor toolRuntime;
    private final AgentProperties agentProperties;
    private final TraceCheckpointRepository checkpoints;
    private final TraceCompletenessGate completenessGate;
    private final TraceStuckDetector stuckDetector;
    private final TraceEvidenceExtractor evidenceExtractor;
    private final JavaSymbolIndex symbolIndex;
    private final ObjectMapper mapper;

    private static final class RuntimeMetrics {
        long decisionMs;
        long toolMs;
        long composeMs;
        int decisionCalls;
        int toolCalls;
    }

    public ChatResponse execute(AskContext context,
                                ConversationSession session,
                                String mode,
                                Integer freeRemaining,
                                long startedAt,
                                AgentExecutor.StreamHooks hooks) {
        String question = context.getQuestion();
        boolean resumeRequested = isContinuation(question);
        TraceState state = resumeRequested ? checkpoints.loadLatestResumable(session.getSessionId()) : null;
        boolean resumed = state != null;
        if (state == null) {
            state = TraceState.start(session.getSessionId(), question, mode, context.getSpecifiedProjects());
        } else {
            state.normalize();
            state.setLatestQuestion(question);
            state.setStatus(TraceStatus.RUNNING);
            state.setFinishRequested(false);
            if (context.getSpecifiedProjects() != null) state.getTargetProjects().addAll(context.getSpecifiedProjects());
        }

        AgentExecutor.Harvest harvest = restoreHarvest(state, context);
        if (resumed) replayStoredEvidence(state, harvest);
        Map<String, Boolean> availability = toolRuntime.computeAvailability();
        Map<String, Integer> failStreak = new HashMap<>();
        List<ChatResponse.Step> steps = new ArrayList<>();
        java.util.function.Consumer<ChatResponse.Step> emit = hooks == null ? null : hooks.onStep();
        if (resumed) {
            addStep(steps, emit, ChatResponse.Step.builder()
                    .no(state.getDecisionRounds()).phase("trace_resume")
                    .summary("恢复未完成调用链：待查 " + state.pendingEdgeCount()
                            + " 条，已确认终点 " + state.getTerminals().size() + " 个")
                    .status("ok").elapsedMs(0).build());
        }

        boolean deepTrace = true;
        int plannedBudget = agentProperties.budgetFor(mode, true);
        int activeBudget = plannedBudget;
        int graceTurns = agentProperties.graceTurnsFor(true);
        boolean graceGranted = false;
        int usedTokens = 0;
        int localRounds = 0;
        boolean tokenGateHit = false;
        boolean elapsedGateHit = false;
        RuntimeMetrics metrics = new RuntimeMetrics();

        List<ToolChatMessage> messages = new ArrayList<>();
        String baseSystem = toolRuntime.buildExploreSystemPrompt(
                mode, availability, plannedBudget, deepTrace, graceTurns);
        messages.add(ToolChatMessage.system(baseSystem + traceControlPrompt()));
        messages.add(ToolChatMessage.user(buildPlannerInput(state, session, context, resumed)));
        List<Map<String, Object>> tools = traceToolSchemas();

        checkpoints.save(state, resumed ? "RESUMED" : "STARTED");

        while (localRounds < activeBudget) {
            toolRuntime.checkCancelled();
            if (localRounds > 0 && System.currentTimeMillis() - startedAt >= agentProperties.getTraceMaxExploreMs()) {
                elapsedGateHit = true;
                state.setIncompleteReason("探索时间预算耗尽（" + agentProperties.getTraceMaxExploreMs() + "ms），已保存进度，可继续续查");
                break;
            }
            localRounds++;
            state.setDecisionRounds(state.getDecisionRounds() + 1);
            int progressBefore = state.progressScore();
            ToolChatResult decision;
            long decisionStarted = System.currentTimeMillis();
            metrics.decisionCalls++;
            try {
                decision = llm.chatWithTools(messages, tools, context.getRt());
            } catch (Exception first) {
                metrics.decisionMs += System.currentTimeMillis() - decisionStarted;
                log.warn("trace 决策轮失败 round={}，重试一次: {}", localRounds, first.getMessage());
                decisionStarted = System.currentTimeMillis();
                metrics.decisionCalls++;
                try {
                    decision = llm.chatWithTools(messages, tools, context.getRt());
                } catch (Exception second) {
                    metrics.decisionMs += System.currentTimeMillis() - decisionStarted;
                    state.setIncompleteReason("模型决策连续失败：" + rootMessage(second));
                    break;
                }
            }
            metrics.decisionMs += System.currentTimeMillis() - decisionStarted;
            if (decision.getPromptTokens() != null) usedTokens += decision.getPromptTokens();
            if (decision.getCompletionTokens() != null) usedTokens += decision.getCompletionTokens();

            List<ToolCall> calls = decision.getToolCalls() == null ? List.of() : decision.getToolCalls();
            if (calls.isEmpty()) {
                TraceCompletenessGate.Result gate = completenessGate.evaluate(state);
                addStep(steps, emit, ChatResponse.Step.builder()
                        .no(state.getDecisionRounds()).phase("closure_check")
                        .summary(gate.summary()).status(gate.complete() ? "ok" : "error")
                        .elapsedMs(0).build());
                if (gate.complete()) break;
                messages.add(ToolChatMessage.assistant(decision.getContent()));
                messages.add(ToolChatMessage.user("闭环门禁未通过：" + gate.summary()
                        + "。不要成文；请从 pending edge 继续查，或用 update_trace 给出有证据的阻断原因。"));
                stuckDetector.registerRoundProgress(state, progressBefore);
                checkpoints.save(state, "CLOSURE_REJECTED");
                if (state.getNoProgressRounds() >= 3) {
                    state.setIncompleteReason("连续多轮没有新增调用边、文件或终点证据");
                    break;
                }
                continue;
            }

            messages.add(ToolChatMessage.builder().role("assistant")
                    .content(decision.getContent() == null ? "" : decision.getContent())
                    .toolCalls(calls).build());

            for (ToolCall call : calls) {
                toolRuntime.checkCancelled();
                long toolStarted = System.currentTimeMillis();
                String signature = canonicalSignature(call);
                addStep(steps, emit, ChatResponse.Step.builder()
                        .no(state.getDecisionRounds()).phase("tool_call")
                        .tool(call.name()).args(pretty(call.arguments()))
                        .status("ok").elapsedMs(0).build());

                LocalToolResult result;
                // update_trace/finish_trace 是有状态控制调用：状态变化后用相同参数重试是合法的。
                // 把它们纳入普通工具去重会导致第一次门禁拒绝后永远无法再次 finish。
                if (!isRepeatableControlCall(call) && !stuckDetector.registerTool(state, signature)) {
                    result = new LocalToolResult(false,
                            "{\"error\":\"DUPLICATE_TOOL_CALL\",\"detail\":\"该工具和参数已经执行过，请使用已有结果推进 pending edge\"}",
                            "跳过重复调用");
                } else {
                    result = execute(call, availability, harvest, failStreak, state);
                }
                collectDeterministicEvidence(call, result, state);
                long elapsed = System.currentTimeMillis() - toolStarted;
                metrics.toolCalls++;
                metrics.toolMs += elapsed;
                addStep(steps, emit, ChatResponse.Step.builder()
                        .no(state.getDecisionRounds()).phase("tool_result")
                        .tool(call.name()).summary(result.summary())
                        .status(result.ok() ? "ok" : "error").elapsedMs(elapsed).build());
                messages.add(ToolChatMessage.tool(call.id(), result.content()));
            }

            int autoExpanded = autoExpandEvidence(messages, availability, harvest, failStreak, state,
                    steps, emit);
            if (autoExpanded > 0) {
                log.debug("状态图批量扩展 round={}, files={}", localRounds, autoExpanded);
            }

            syncStateFromHarvest(state, harvest);
            if (state.pendingEdgeCount() == 0 && !state.getTerminals().isEmpty()
                    && state.getUnresolvedReasons().isEmpty()) {
                // 证据已经满足程序门禁时自动提交结束，不再浪费一轮等模型调用 finish_trace。
                state.setFinishRequested(true);
            }
            boolean progressed = stuckDetector.registerRoundProgress(state, progressBefore);
            toolRuntime.compressIfNeeded(messages);
            checkpoints.save(state, progressed ? "PROGRESSED" : "NO_PROGRESS");

            TraceCompletenessGate.Result gate = completenessGate.evaluate(state);
            if (gate.complete()) {
                addStep(steps, emit, ChatResponse.Step.builder()
                        .no(state.getDecisionRounds()).phase("closure_check")
                        .summary(gate.summary()).status("ok").elapsedMs(0).build());
                break;
            }
            if (state.isFinishRequested()) {
                // 模型已经完成覆盖审计并申请结束时，立即做一次只提交状态的收尾，
                // 不必等 30 轮预算耗尽后才把最后已读证据写回状态图。
                commitLatestEvidence(context, state, harvest, steps, emit, metrics);
                gate = completenessGate.evaluate(state);
                if (gate.complete()) {
                    addStep(steps, emit, ChatResponse.Step.builder()
                            .no(state.getDecisionRounds()).phase("closure_check")
                            .summary(gate.summary()).status("ok").elapsedMs(0).build());
                    break;
                }
                state.setFinishRequested(false);
                messages.add(ToolChatMessage.user("finish_trace 被闭环门禁拒绝：" + gate.summary()
                        + "。继续批量处理所有 pending edge；缺少源码时登记明确阻断项。"));
            }
            if (stuckDetector.isStuck(state)) {
                addStep(steps, emit, ChatResponse.Step.builder()
                        .no(state.getDecisionRounds()).phase("replan")
                        .summary("连续两轮无新增证据，要求从待查边重新规划且禁止重复工具调用")
                        .status("error").elapsedMs(0).build());
                messages.add(ToolChatMessage.user("你连续两轮没有新增事实。请停止改写相同关键词，"
                        + "优先用 find_symbol 精确定位 pending edge 的目标类型，或换到提供方项目。"));
                if (state.getNoProgressRounds() >= 3) {
                    state.setIncompleteReason("连续三轮没有新增调用边、文件或终点证据，已停止无效重复探索");
                    break;
                }
            }
            if (usedTokens > agentProperties.getTokenBudget()) {
                tokenGateHit = true;
                state.setIncompleteReason("token 预算耗尽（" + usedTokens + "）");
                break;
            }
            if (System.currentTimeMillis() - startedAt >= agentProperties.getTraceMaxExploreMs()) {
                elapsedGateHit = true;
                state.setIncompleteReason("探索时间预算耗尽（" + agentProperties.getTraceMaxExploreMs() + "ms），已保存进度，可继续续查");
                break;
            }
            if (!graceGranted && progressed && localRounds >= activeBudget && graceTurns > 0) {
                int extended = Math.min(activeBudget + graceTurns, AgentProperties.HARD_CAP_BUDGET);
                if (extended > activeBudget) {
                    graceGranted = true;
                    activeBudget = extended;
                    addStep(steps, emit, ChatResponse.Step.builder()
                            .no(state.getDecisionRounds()).phase("budget_extension")
                            .summary("状态图仍有新增事实，追加 " + (extended - localRounds) + " 轮收尾")
                            .status("ok").elapsedMs(0).build());
                }
            }
        }

        syncStateFromHarvest(state, harvest);
        commitLatestEvidence(context, state, harvest, steps, emit, metrics);
        TraceCompletenessGate.Result finalGate = completenessGate.evaluate(state);
        boolean complete = finalGate.complete();
        if (complete) {
            state.setStatus(TraceStatus.COMPLETE);
            state.setIncompleteReason(null);
        } else {
            state.setStatus(TraceStatus.PARTIAL);
            if (state.getIncompleteReason() == null || state.getIncompleteReason().isBlank()) {
                String budget = tokenGateHit ? "token 预算耗尽"
                        : elapsedGateHit ? "探索时间预算耗尽" : "本次决策预算耗尽";
                state.setIncompleteReason(budget + "；" + finalGate.summary());
            }
        }
        checkpoints.save(state, complete ? "COMPLETED" : "PARTIAL");

        long composeStarted = System.currentTimeMillis();
        Answer answer = composeAnswer(context, session, mode, harvest, state, complete, hooks);
        metrics.composeMs += System.currentTimeMillis() - composeStarted;
        if (!complete) {
            answer = new Answer("⚠️ 本次调用链尚未完全闭环，以下内容区分了已验证事实与待确认分支。\n\n"
                    + answer.text(), "high".equals(answer.confidence()) ? "medium" : answer.confidence());
        }

        session.append(new ChatMessage("user", question));
        session.append(new ChatMessage("assistant", answer.text()));
        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("=== trace 回答完成 traceId={}, 耗时={}ms, rounds={}, edges={}, terminals={}, completion={} ===",
                state.getTraceId(), elapsed, localRounds, state.getEdges().size(), state.getTerminals().size(),
                complete ? "complete" : "partial");
        return buildResponse(state, harvest, session, mode, freeRemaining, elapsed, answer, steps, complete, metrics);
    }

    private LocalToolResult execute(ToolCall call,
                                    Map<String, Boolean> availability,
                                    AgentExecutor.Harvest harvest,
                                    Map<String, Integer> failStreak,
                                    TraceState state) {
        try {
            JsonNode args = mapper.readTree(call.arguments() == null ? "{}" : call.arguments());
            ToolCall normalizedCall = normalizeToolCall(call, args);
            args = mapper.readTree(normalizedCall.arguments());
            return switch (normalizedCall.name() == null ? "" : normalizedCall.name()) {
                case "find_symbol" -> findSymbol(args, harvest);
                case "update_trace" -> updateTrace(args, harvest, state);
                case "finish_trace" -> requestFinish(state);
                case "search_code" -> smartSearch(normalizedCall, args, availability, harvest, failStreak);
                default -> {
                    AgentExecutor.ExecResult legacy = toolRuntime.executeTool(normalizedCall, availability, harvest, failStreak);
                    yield new LocalToolResult(legacy.ok(), legacy.content(), legacy.summary());
                }
            };
        } catch (Exception e) {
            return new LocalToolResult(false,
                    "{\"error\":\"INVALID_ARGS\",\"detail\":\"" + jsonEscape(rootMessage(e)) + "\"}",
                    "参数处理失败: " + rootMessage(e));
        }
    }

    /** 部分兼容模型会把 schema 中的单元素数组输出成字符串；在工具边界统一纠正。 */
    private ToolCall normalizeToolCall(ToolCall call, JsonNode parsed) {
        if (!(parsed instanceof ObjectNode args)) return call;
        boolean changed = false;
        for (String field : List.of("keywords", "project_keys")) {
            JsonNode value = args.get(field);
            if (value != null && value.isTextual()) {
                String text = value.asText("").trim();
                ArrayNode array = mapper.createArrayNode();
                if (!text.isBlank()) array.add(text);
                args.set(field, array);
                changed = true;
            }
        }
        return changed ? new ToolCall(call.id(), call.name(), args.toString()) : call;
    }

    /** 明确的 class/interface Xxx 全库搜索自动改走文件名符号索引。 */
    private LocalToolResult smartSearch(ToolCall call,
                                        JsonNode args,
                                        Map<String, Boolean> availability,
                                        AgentExecutor.Harvest harvest,
                                        Map<String, Integer> failStreak) {
        List<String> projects = new ArrayList<>();
        args.path("project_keys").forEach(n -> projects.add(n.asText()));
        if (projects.isEmpty() && args.path("keywords").size() == 1) {
            String keyword = args.path("keywords").get(0).asText("").trim();
            Matcher matcher = Pattern.compile("(?i)^(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)$")
                    .matcher(keyword);
            if (matcher.matches()) {
                ObjectNode redirected = mapper.createObjectNode();
                redirected.put("symbol", matcher.group(1));
                redirected.putArray("project_keys");
                LocalToolResult exact = findSymbol(redirected, harvest);
                return new LocalToolResult(exact.ok(), exact.content(),
                        "符号索引替代全库搜索；" + exact.summary());
            }
        }
        AgentExecutor.ExecResult legacy = toolRuntime.executeTool(call, availability, harvest, failStreak);
        return new LocalToolResult(legacy.ok(), legacy.content(), legacy.summary());
    }

    /**
     * 模型完成一批 search/find 后，程序直接把精确符号候选和 Top 命中文件批量读入。
     * 文件读取是本地毫秒级操作，不值得再付一次 LLM 往返；下一轮模型可直接基于源码更新整批边。
     */
    private int autoExpandEvidence(List<ToolChatMessage> messages,
                                   Map<String, Boolean> availability,
                                   AgentExecutor.Harvest harvest,
                                   Map<String, Integer> failStreak,
                                   TraceState state,
                                   List<ChatResponse.Step> steps,
                                   java.util.function.Consumer<ChatResponse.Step> emit) {
        int limit = Math.max(0, agentProperties.getTraceAutoExpandFiles());
        if (limit == 0) return 0;

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (TraceEdge edge : state.getEdges().values()) {
            if (!edge.isPending()) continue;
            String symbol = targetJavaSymbol(edge.getToSymbol());
            if (symbol.isBlank()) continue;
            for (JavaSymbolIndex.Hit hit : symbolIndex.find(symbol, List.of())) {
                if (!harvest.readFiles.contains(hit.file())) candidates.add(hit.file());
                if (candidates.size() >= limit) break;
            }
            if (candidates.size() >= limit) break;
        }
        // 普通 search_code 的 Top 命中经常包含相邻接口、测试类和兼容入口。
        // 自动整批读取它们会放大上下文且污染调查方向；这些候选仍交给模型按需选择。
        if (candidates.isEmpty()) return 0;

        List<ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (String file : candidates) {
            ObjectNode args = mapper.createObjectNode();
            args.put("file_path", file);
            calls.add(new ToolCall("auto-read-" + state.getDecisionRounds() + "-" + (++index),
                    "read_file", args.toString()));
        }
        messages.add(ToolChatMessage.builder().role("assistant")
                .content("程序根据当前 pending edge 和检索命中批量读取候选文件。")
                .toolCalls(calls).build());

        int expanded = 0;
        for (ToolCall call : calls) {
            toolRuntime.checkCancelled();
            long started = System.currentTimeMillis();
            LocalToolResult result;
            if (!stuckDetector.registerTool(state, canonicalSignature(call))) {
                result = new LocalToolResult(false,
                        "{\"error\":\"DUPLICATE_TOOL_CALL\",\"detail\":\"自动批量读取已执行\"}",
                        "跳过重复自动读取");
            } else {
                result = execute(call, availability, harvest, failStreak, state);
            }
            collectDeterministicEvidence(call, result, state);
            if (result.ok()) expanded++;
            addStep(steps, emit, ChatResponse.Step.builder()
                    .no(state.getDecisionRounds()).phase("auto_expand")
                    .tool(call.name()).args(pretty(call.arguments())).summary(result.summary())
                    .status(result.ok() ? "ok" : "error")
                    .elapsedMs(System.currentTimeMillis() - started).build());
            messages.add(ToolChatMessage.tool(call.id(), result.content()));
        }
        toolRuntime.compressIfNeeded(messages);
        return expanded;
    }

    private String targetJavaSymbol(String target) {
        if (target == null || target.isBlank()) return "";
        String compact = target.split("[（(\\s]", 2)[0];
        String[] parts = compact.split("[.#]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].replaceAll("[^A-Za-z0-9_$]", "");
            if (!part.isBlank() && Character.isUpperCase(part.charAt(0))) return part;
        }
        return "";
    }

    private void collectDeterministicEvidence(ToolCall call, LocalToolResult result, TraceState state) {
        if (!result.ok() || call.name() == null
                || !("read_file".equals(call.name()) || "search_in_file".equals(call.name()))) return;
        try {
            JsonNode args = mapper.readTree(call.arguments() == null ? "{}" : call.arguments());
            String file = normalizedFile(args.path("file_path").asText(""));
            TraceEvidenceExtractor.Extraction extraction = evidenceExtractor.collect(state, file, result.content());
            if (extraction.resolvedEdges() > 0 || extraction.terminals() > 0) {
                log.debug("确定性证据提取 file={}, resolvedEdges={}, terminals={}",
                        file, extraction.resolvedEdges(), extraction.terminals());
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 搜索预算结束后只允许提交状态，不再允许展开新搜索。
     * 解决“最后一轮刚读到终点，却没有下一轮 update_trace”的结构性缺口。
     */
    private void commitLatestEvidence(AskContext context,
                                      TraceState state,
                                      AgentExecutor.Harvest harvest,
                                      List<ChatResponse.Step> steps,
                                      java.util.function.Consumer<ChatResponse.Step> emit,
                                      RuntimeMetrics metrics) {
        TraceCompletenessGate.Result before = completenessGate.evaluate(state);
        if (before.complete() || harvest.materials.isEmpty()) return;
        try {
            List<ToolChatMessage> messages = List.of(
                    ToolChatMessage.system("""
                            你是调用链状态提交器，不再搜索、不回答用户。根据已读材料更新状态图：
                            1) 已经读到目标实现文件的 pending edge 改为 resolved；
                            2) 只有具体 Mapper/Mongo/Redis/ES/MQ/文件客户端调用才能登记 terminal；
                            3) 没有证据的边保持 pending；
                            4) 调用 update_trace；若确实没有 pending 和阻断，再同时调用 finish_trace。
                            """),
                    ToolChatMessage.user(stateSummary(state) + "\n\n最近材料：\n"
                            + recentMaterials(harvest, agentProperties.getTraceCommitContextBytes())));
            long decisionStarted = System.currentTimeMillis();
            metrics.decisionCalls++;
            ToolChatResult result;
            try {
                result = llm.chatWithTools(messages, controlToolSchemas(), context.getRt());
            } finally {
                metrics.decisionMs += System.currentTimeMillis() - decisionStarted;
            }
            state.setDecisionRounds(state.getDecisionRounds() + 1);
            List<ToolCall> calls = result.getToolCalls() == null ? List.of() : result.getToolCalls();
            for (ToolCall call : calls) {
                long toolStarted = System.currentTimeMillis();
                LocalToolResult local = execute(call, Map.of(), harvest, new HashMap<>(), state);
                long toolElapsed = System.currentTimeMillis() - toolStarted;
                metrics.toolCalls++;
                metrics.toolMs += toolElapsed;
                addStep(steps, emit, ChatResponse.Step.builder()
                        .no(state.getDecisionRounds()).phase("closure_commit")
                        .tool(call.name()).args(pretty(call.arguments())).summary(local.summary())
                        .status(local.ok() ? "ok" : "error").elapsedMs(toolElapsed).build());
            }
            TraceCompletenessGate.Result after = completenessGate.evaluate(state);
            if (!state.isFinishRequested() && after.reasons().size() == 1
                    && after.reasons().contains("模型尚未提交闭环检查")) {
                state.setFinishRequested(true);
            }
            checkpoints.save(state, "EVIDENCE_COMMITTED");
        } catch (Exception e) {
            log.warn("闭环状态提交失败，保留现有 checkpoint: {}", rootMessage(e));
        }
    }

    private String recentMaterials(AgentExecutor.Harvest harvest, int cap) {
        List<String> materials = toolRuntime.distillMaterials(harvest);
        StringBuilder out = new StringBuilder();
        for (int i = materials.size() - 1; i >= 0; i--) {
            String material = materials.get(i);
            if (out.length() + material.length() > cap) break;
            out.insert(0, material + "\n");
        }
        return out.toString();
    }

    private LocalToolResult findSymbol(JsonNode args, AgentExecutor.Harvest harvest) {
        String symbol = args.path("symbol").asText("").trim();
        List<String> projects = new ArrayList<>();
        args.path("project_keys").forEach(n -> projects.add(n.asText()));
        if (symbol.isBlank()) return error("INVALID_ARGS", "symbol 不能为空");
        List<JavaSymbolIndex.Hit> hits = symbolIndex.find(symbol, projects);
        ObjectNode root = mapper.createObjectNode();
        ArrayNode results = root.putArray("results");
        for (JavaSymbolIndex.Hit hit : hits) {
            ObjectNode item = results.addObject();
            item.put("symbol", hit.symbol());
            item.put("project", hit.project());
            item.put("file", hit.file());
            harvest.searchedProjects.add(hit.project());
        }
        if (hits.isEmpty()) root.put("note", "符号索引无精确文件名命中，请在限定项目内 search_code 搜 implements/方法名");
        String content = root.toString();
        if (!hits.isEmpty()) harvest.materials.add("【find_symbol " + symbol + "】\n" + content);
        return new LocalToolResult(true, content, "精确定位 " + hits.size() + " 个候选文件");
    }

    private LocalToolResult updateTrace(JsonNode args,
                                        AgentExecutor.Harvest harvest,
                                        TraceState state) {
        int acceptedEdges = 0;
        int acceptedTerminals = 0;
        List<String> warnings = new ArrayList<>();
        if (args.path("clear_unresolved").asBoolean(false)) state.getUnresolvedReasons().clear();

        for (JsonNode node : args.path("edges")) {
            String evidenceFile = normalizedFile(node.path("evidence_file").asText(""));
            String status = node.path("status").asText("pending").toLowerCase(Locale.ROOT);
            String reason = node.path("reason").asText("");
            boolean excluded = "excluded".equals(status) && !reason.isBlank();
            if (!"pending".equals(status) && !excluded && !harvest.readFiles.contains(evidenceFile)) {
                warnings.add("调用边证据文件尚未实读，保持 pending: " + node.path("to").asText());
                status = "pending";
            }
            TraceEdge edge = TraceEdge.builder()
                    .fromSymbol(node.path("from").asText("unknown"))
                    .toSymbol(node.path("to").asText(""))
                    .kind(node.path("kind").asText("call"))
                    .status(status)
                    .evidenceFile(evidenceFile.isBlank() ? null : evidenceFile)
                    .evidenceLine(positiveInt(node.path("evidence_line")))
                    .reason(reason.isBlank() ? null : reason)
                    .build();
            int before = state.getEdges().size();
            state.putEdge(edge);
            if (state.getEdges().size() > before || !edge.isPending()) acceptedEdges++;
        }

        for (JsonNode node : args.path("terminals")) {
            String evidenceFile = normalizedFile(node.path("evidence_file").asText(""));
            if (evidenceFile.isBlank() || !harvest.readFiles.contains(evidenceFile)) {
                warnings.add("终点证据文件尚未实读，未接纳: " + node.path("symbol").asText());
                continue;
            }
            TraceTerminal terminal = TraceTerminal.builder()
                    .symbol(node.path("symbol").asText(""))
                    .type(node.path("type").asText("unknown"))
                    .evidenceFile(evidenceFile)
                    .evidenceLine(positiveInt(node.path("evidence_line")))
                    .detail(node.path("detail").asText(""))
                    .build();
            int before = state.getTerminals().size();
            state.putTerminal(terminal);
            if (state.getTerminals().size() > before) acceptedTerminals++;
        }

        for (JsonNode reason : args.path("unresolved_reasons")) {
            String text = reason.asText("").trim();
            // “已闭环/已登记外部终点”不是未解决阻断。模型偶尔会把证明性说明误放进
            // unresolved_reasons，若照单全收会让闭环门禁永久拒绝 finish_trace。
            if (isResolvedExplanation(text)) {
                warnings.add("已忽略非阻断说明: " + text);
            } else if (!text.isBlank() && !state.getUnresolvedReasons().contains(text)) {
                state.getUnresolvedReasons().add(text);
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("acceptedEdges", acceptedEdges);
        result.put("acceptedTerminals", acceptedTerminals);
        result.put("pendingEdges", state.pendingEdgeCount());
        result.put("unresolved", state.getUnresolvedReasons().size());
        ArrayNode warningArray = result.putArray("warnings");
        warnings.forEach(warningArray::add);
        return new LocalToolResult(true, result.toString(),
                "状态图更新：边+" + acceptedEdges + "，终点+" + acceptedTerminals
                        + "，待查=" + state.pendingEdgeCount());
    }

    private LocalToolResult requestFinish(TraceState state) {
        state.setFinishRequested(true);
        TraceCompletenessGate.Result gate = completenessGate.evaluate(state);
        ObjectNode result = mapper.createObjectNode();
        result.put("accepted", gate.complete());
        result.put("summary", gate.summary());
        result.put("pendingEdges", state.pendingEdgeCount());
        result.put("terminals", state.getTerminals().size());
        return new LocalToolResult(gate.complete(), result.toString(), gate.summary());
    }

    private Answer composeAnswer(AskContext context,
                                 ConversationSession session,
                                 String mode,
                                 AgentExecutor.Harvest harvest,
                                 TraceState state,
                                 boolean complete,
                                 AgentExecutor.StreamHooks hooks) {
        if (harvest.materials.isEmpty()) {
            return new Answer("没有在本地代码库中获得足够材料。" + state.getIncompleteReason(), "low");
        }
        String system = toolRuntime.buildFinalSystemPrompt(
                mode, new ArrayList<>(state.getSearchedProjects()), context.useFlowchart());
        String user = buildFinalInput(context.getQuestion(), session, harvest, state, complete);
        try {
            String raw = hooks != null && hooks.onDelta() != null
                    ? llm.chatStream(system, user, context.getRt(), hooks.onDelta())
                    : llm.chat(system, user, context.getRt());
            String[] parsed = toolRuntime.parseConfidence(raw);
            return new Answer(parsed[0], parsed[1]);
        } catch (Exception e) {
            log.error("trace 成文失败", e);
            return new Answer("调用链材料已经保存，但生成回答失败：" + rootMessage(e), "low");
        }
    }

    private ChatResponse buildResponse(TraceState state,
                                       AgentExecutor.Harvest harvest,
                                       ConversationSession session,
                                       String mode,
                                       Integer freeRemaining,
                                       long elapsed,
                                       Answer answer,
                                       List<ChatResponse.Step> steps,
                                       boolean complete,
                                       RuntimeMetrics metrics) {
        LinkedHashSet<String> files = new LinkedHashSet<>(state.getReadFiles());
        for (SearchResult hit : harvest.codeHits.values()) files.add(toolRuntime.toRelative(hit.getFilePath()));
        Map<String, LinkedHashSet<Integer>> lines = new LinkedHashMap<>();
        for (TraceEdge edge : state.getEdges().values()) {
            if (edge.getEvidenceFile() != null && edge.getEvidenceLine() != null) {
                lines.computeIfAbsent(edge.getEvidenceFile(), ignored -> new LinkedHashSet<>()).add(edge.getEvidenceLine());
            }
        }
        for (TraceTerminal terminal : state.getTerminals().values()) {
            if (terminal.getEvidenceFile() != null && terminal.getEvidenceLine() != null) {
                lines.computeIfAbsent(terminal.getEvidenceFile(), ignored -> new LinkedHashSet<>()).add(terminal.getEvidenceLine());
            }
        }
        List<String> sourceFiles = files.stream().limit(14).toList();
        List<ChatResponse.SourceRef> sources = sourceFiles.stream().map(file -> ChatResponse.SourceRef.builder()
                .project(projectOf(file)).file(file)
                .lines(new ArrayList<>(lines.getOrDefault(file, new LinkedHashSet<>())))
                .entry(file.endsWith("Controller.java")).build()).toList();
        return ChatResponse.builder()
                .answer(answer.text()).sessionId(session.getSessionId()).mode(mode)
                .matchedProjects(new ArrayList<>(state.getSearchedProjects()))
                .matchedFeatures(List.of()).sourceFiles(sourceFiles).sources(sources)
                .confidence(answer.confidence())
                .needHuman("low".equalsIgnoreCase(answer.confidence()))
                .needKey(false).freeRemaining(freeRemaining).elapsedMs(elapsed)
                .engine("trace").steps(steps).stepsUsed(state.getDecisionRounds())
                .completionStatus(complete ? "complete" : "partial")
                .incompleteReason(state.getIncompleteReason())
                .traceId(state.getTraceId()).pendingEdges(state.pendingEdgeCount())
                .terminalCount(state.getTerminals().size())
                .performance(ChatResponse.Performance.builder().route("trace")
                        .totalMs(elapsed).decisionMs(metrics.decisionMs)
                        .toolMs(metrics.toolMs).composeMs(metrics.composeMs)
                        .decisionCalls(metrics.decisionCalls).toolCalls(metrics.toolCalls).build())
                .build();
    }

    private AgentExecutor.Harvest restoreHarvest(TraceState state, AskContext context) {
        AgentExecutor.Harvest harvest = new AgentExecutor.Harvest();
        harvest.searchedProjects.addAll(state.getSearchedProjects());
        harvest.readFiles.addAll(state.getReadFiles());
        harvest.materials.addAll(state.getMaterials());
        if (context.getSpecifiedProjects() != null) harvest.specifiedProjects = context.getSpecifiedProjects();
        else if (!state.getTargetProjects().isEmpty()) harvest.specifiedProjects = new ArrayList<>(state.getTargetProjects());
        return harvest;
    }

    /**
     * 旧 checkpoint 中的材料是在确定性证据提取器上线前保存的。恢复任务时重放这些已读源码，
     * 这样续查可以直接继承已经确认的数据终点，不必浪费轮次重复读取同一批文件。
     */
    private void replayStoredEvidence(TraceState state, AgentExecutor.Harvest harvest) {
        int resolvedEdges = 0;
        int terminals = 0;
        for (String material : harvest.materials) {
            if (material == null) continue;
            Matcher matcher = STORED_SOURCE_MATERIAL.matcher(material);
            if (!matcher.matches()) continue;
            TraceEvidenceExtractor.Extraction extraction = evidenceExtractor.collect(
                    state, normalizedFile(matcher.group(1)), matcher.group(2));
            resolvedEdges += extraction.resolvedEdges();
            terminals += extraction.terminals();
        }
        if (resolvedEdges > 0 || terminals > 0) {
            log.info("恢复 checkpoint 时重放源码证据：resolvedEdges={}, terminals={}", resolvedEdges, terminals);
        }
    }

    private void syncStateFromHarvest(TraceState state, AgentExecutor.Harvest harvest) {
        state.getSearchedProjects().addAll(harvest.searchedProjects);
        state.getReadFiles().addAll(harvest.readFiles);
        state.setMaterials(new ArrayList<>(harvest.materials));
    }

    private String buildPlannerInput(TraceState state,
                                     ConversationSession session,
                                     AskContext context,
                                     boolean resumed) {
        return """
                用户本轮问题：%s
                原始调查目标：%s
                %s

                当前状态图：
                %s

                历史对话：
                %s

                从状态图的 pending edge 开始工作。先用 find_symbol 精确定位明确类名；只有精确定位失败时才 search_code。
                每次从源码确认新调用边或数据终点后，必须调用 update_trace 登记证据。
                """.formatted(context.getQuestion(), state.getOriginalQuestion(),
                resumed ? "这是续查任务，不得重复已经执行过的工具和已读文件。" : "这是新调查任务。",
                stateSummary(state), toolRuntime.renderHistory(session));
    }

    private String buildFinalInput(String question,
                                   ConversationSession session,
                                   AgentExecutor.Harvest harvest,
                                   TraceState state,
                                   boolean complete) {
        StringBuilder materials = new StringBuilder();
        materials.append(recentMaterials(harvest, agentProperties.getTraceFinalContextBytes()));
        return """
                用户问题：%s

                历史对话：
                %s

                程序验证的调用链状态：%s
                %s

                唯一可信材料：
                %s

                只允许根据材料和状态图回答。状态为 PARTIAL 时必须逐项列出未闭环分支，
                不得把接口、Feign、抽象 Service 当成数据终点。
                回答优先给结论和主链路，合并同类数据终点，控制在 2500 个中文字符以内；
                不要逐条复述重复的 Repository/Mapper 调用。
                """.formatted(question, toolRuntime.renderHistory(session),
                complete ? "COMPLETE" : "PARTIAL", stateSummary(state), materials);
    }

    private String stateSummary(TraceState state) {
        StringBuilder out = new StringBuilder();
        out.append("traceId=").append(state.getTraceId())
                .append("；待查边=").append(state.pendingEdgeCount())
                .append("；终点=").append(state.getTerminals().size()).append('\n');
        if (!state.getEdges().isEmpty()) {
            out.append("调用边：\n");
            state.getEdges().values().stream().limit(60).forEach(edge -> out.append("- [")
                    .append(edge.getStatus()).append("] ").append(edge.getFromSymbol())
                    .append(" -> ").append(edge.getToSymbol())
                    .append(edge.getEvidenceFile() == null ? "" : " @ " + edge.getEvidenceFile()
                            + (edge.getEvidenceLine() == null ? "" : ":" + edge.getEvidenceLine()))
                    .append('\n'));
        }
        if (!state.getTerminals().isEmpty()) {
            out.append("已确认终点：\n");
            state.getTerminals().values().forEach(terminal -> out.append("- ")
                    .append(terminal.getType()).append("｜").append(terminal.getSymbol())
                    .append(" @ ").append(terminal.getEvidenceFile())
                    .append(terminal.getEvidenceLine() == null ? "" : ":" + terminal.getEvidenceLine())
                    .append('\n'));
        }
        if (!state.getUnresolvedReasons().isEmpty()) {
            out.append("阻断项：\n");
            state.getUnresolvedReasons().forEach(reason -> out.append("- ").append(reason).append('\n'));
        }
        return out.toString();
    }

    private String traceControlPrompt() {
        return """

                ## 状态图控制协议（必须遵守）
                - 你不是用轮数猜测是否完成，而是维护一张有证据的调用链图。
                - 明确类名/接口名先 find_symbol；避免一上来全库 search_code。
                - 读到 Controller→Service、Feign/RPC→提供方、Service→Repository 等关系后，
                  用 update_trace 登记边。尚未看到目标实现时 status=pending；已实读证据后 status=resolved。
                - DB/Mongo/ES/Redis/MQ/文件存储/明确外部系统才可登记 terminals。
                - resolved 边和 terminal 的 evidence_file 必须是本次或 checkpoint 中实际 read_file/search_in_file 读过的文件。
                - 发现多分支时全部登记为 pending，逐条处理，不能只查主分支后口头声称完整。
                - 源码缺失、动态配置或反射确实无法确认时，用 unresolved_reasons 写清具体阻断，不能猜测。
                - unresolved_reasons 只登记仍未闭环、会导致 PARTIAL 的真实阻断；若已经登记为 external terminal，
                  或文字本身是“已闭环”的证明，不要再放入 unresolved_reasons。
                - 所有 pending edge 处理完后调用 finish_trace。程序闭环门禁未通过时必须继续查。
                - 每轮必须尽量一次发出 4～10 个互不依赖的工具调用：把多个 pending 分支的
                  find_symbol/search_code/read_file 合并在同一轮，不要一次只处理一个文件。
                - update_trace 必须与下一批搜索工具在同一轮并行调用；程序还会自动批量读取
                  精确符号候选和 Top 命中文件，下一轮直接消费这些源码，禁止再次请求同一文件。
                """;
    }

    private List<Map<String, Object>> traceToolSchemas() {
        List<Map<String, Object>> tools = new ArrayList<>(toolRuntime.toolSchemas());
        tools.add(function("find_symbol", "按 Java 类/接口文件名精确定位，不扫描文件全文；明确类型名时优先于 search_code。",
                Map.of("type", "object", "properties", Map.of(
                                "symbol", Map.of("type", "string", "description", "类名/接口名，可带包名"),
                                "project_keys", Map.of("type", "array", "items", Map.of("type", "string"))),
                        "required", List.of("symbol"))));

        Map<String, Object> edgeProperties = new LinkedHashMap<>();
        edgeProperties.put("from", Map.of("type", "string"));
        edgeProperties.put("to", Map.of("type", "string"));
        edgeProperties.put("kind", Map.of("type", "string", "description", "local/rpc/feign/repository/mapper/other"));
        edgeProperties.put("status", Map.of("type", "string", "enum", List.of("pending", "resolved", "excluded")));
        edgeProperties.put("evidence_file", Map.of("type", "string"));
        edgeProperties.put("evidence_line", Map.of("type", "integer"));
        edgeProperties.put("reason", Map.of("type", "string"));
        Map<String, Object> terminalProperties = new LinkedHashMap<>();
        terminalProperties.put("symbol", Map.of("type", "string"));
        terminalProperties.put("type", Map.of("type", "string", "description", "mysql/mongo/es/redis/mq/file/external"));
        terminalProperties.put("evidence_file", Map.of("type", "string"));
        terminalProperties.put("evidence_line", Map.of("type", "integer"));
        terminalProperties.put("detail", Map.of("type", "string"));
        Map<String, Object> updateProperties = new LinkedHashMap<>();
        updateProperties.put("edges", Map.of("type", "array", "items", Map.of(
                "type", "object", "properties", edgeProperties, "required", List.of("from", "to", "status"))));
        updateProperties.put("terminals", Map.of("type", "array", "items", Map.of(
                "type", "object", "properties", terminalProperties,
                "required", List.of("symbol", "type", "evidence_file"))));
        updateProperties.put("unresolved_reasons", Map.of("type", "array", "items", Map.of("type", "string")));
        updateProperties.put("clear_unresolved", Map.of("type", "boolean"));
        tools.add(function("update_trace", "把已从源码确认的调用边、待查边和数据终点写入状态图。",
                Map.of("type", "object", "properties", updateProperties)));
        tools.add(function("finish_trace", "请求结束调查；只有程序确认无 pending/阻断且已有终点证据时才会接受。",
                Map.of("type", "object", "properties", Map.of())));
        return tools;
    }

    private List<Map<String, Object>> controlToolSchemas() {
        return traceToolSchemas().stream()
                .filter(tool -> {
                    Object function = tool.get("function");
                    if (!(function instanceof Map<?, ?> map)) return false;
                    Object name = map.get("name");
                    return "update_trace".equals(name) || "finish_trace".equals(name);
                })
                .toList();
    }

    private Map<String, Object> function(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", description, "parameters", parameters));
    }

    private void addStep(List<ChatResponse.Step> steps,
                         java.util.function.Consumer<ChatResponse.Step> emit,
                         ChatResponse.Step step) {
        steps.add(step);
        if (emit != null) emit.accept(step);
    }

    private boolean isContinuation(String question) {
        if (question == null) return false;
        String normalized = question.toLowerCase(Locale.ROOT);
        return CONTINUATION_WORDS.stream().anyMatch(normalized::contains);
    }

    private String canonicalSignature(ToolCall call) {
        try {
            JsonNode args = mapper.readTree(call.arguments() == null ? "{}" : call.arguments());
            return call.name() + "|" + args.toString();
        } catch (Exception ignored) {
            return call.name() + "|" + call.arguments();
        }
    }

    private boolean isRepeatableControlCall(ToolCall call) {
        return call != null && ("update_trace".equals(call.name()) || "finish_trace".equals(call.name()));
    }

    private boolean isResolvedExplanation(String text) {
        if (text == null || text.isBlank()) return false;
        String compact = text.replaceAll("\\s+", "");
        return compact.startsWith("已闭环")
                || compact.contains("按外部系统登记终点")
                || compact.contains("已登记为externalterminal");
    }

    private String pretty(String json) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    mapper.readTree(json == null ? "{}" : json));
        } catch (Exception ignored) {
            return json;
        }
    }

    private String normalizedFile(String file) {
        return file == null ? "" : file.trim().replace('\\', '/');
    }

    private Integer positiveInt(JsonNode node) {
        int value = node.asInt(0);
        return value > 0 ? value : null;
    }

    private String projectOf(String file) {
        int slash = file.indexOf('/');
        return slash > 0 ? file.substring(0, slash) : "";
    }

    private LocalToolResult error(String code, String message) {
        return new LocalToolResult(false,
                "{\"error\":\"" + code + "\",\"detail\":\"" + jsonEscape(message) + "\"}",
                code + ": " + message);
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record LocalToolResult(boolean ok, String content, String summary) {}
    private record Answer(String text, String confidence) {}
}
