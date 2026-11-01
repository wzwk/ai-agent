package dev.example.productai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.example.productai.config.AgentProperties;
import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.llm.LlmCancelledException;
import dev.example.productai.llm.LlmCancellation;
import dev.example.productai.llm.LlmClient;
import dev.example.productai.llm.ToolCall;
import dev.example.productai.llm.ToolChatMessage;
import dev.example.productai.llm.ToolChatResult;
import dev.example.productai.model.ChatMessage;
import dev.example.productai.model.ChatResponse;
import dev.example.productai.model.ConversationSession;
import dev.example.productai.model.ProjectInfo;
import dev.example.productai.search.CodeSearcher;
import dev.example.productai.search.MarkdownDocumentIndex;
import dev.example.productai.search.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 智能体执行器：模型在循环里自主决定调哪个工具、搜什么、何时收敛。
 * - 决策轮（非流式 function calling）与成文轮（普通调用）分离
 * - 预算按"决策轮"计，耗尽软着陆（基于已有材料强制成文，不报错）
 * - 工具错误结构化回喂，由模型自修参数；同工具同参数连败 2 次回 TOOL_FAILED
 * - 项目可用性每次请求检查（目录存在且非空），克隆后自动恢复
 * - 工具结果累计超限压缩最旧的（上下文工程）
 *
 * @author wengzewei
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecutor implements AgentToolRuntime {

    private static final Pattern API_PATH_IN_TEXT =
            Pattern.compile("/[a-zA-Z][\\w-]*(?:/[\\w-]+)+");
    /** 业务公共组件约定：SwitcherID(dataID, groupID, type)。 */
    private static final Pattern SWITCHER_ID_LITERAL = Pattern.compile(
            "new\\s+SwitcherID\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"");

    private final LlmClient llm;
    private final CodeSearcher searcher;
    private final ProjectsConfigLoader loader;
    private final ProjectsProperties projectsProps;
    private final SearchProperties searchProps;
    private final AgentProperties agentProps;
    private final dev.example.productai.rag.VectorSearchService vectorSearch;
    private final MarkdownDocumentIndex documentIndex;
    private final ToolExecutionGuard toolExecutionGuard;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final class RuntimeMetrics {
        long decisionMs;
        long toolMs;
        long composeMs;
        int decisionCalls;
        int toolCalls;
    }

    private record RouteControllerCandidate(Path path, int hitLine, int score) {}

    // ==================================================================================
    // 入口（由 ProductAgent 在配额检查后调用）
    // ==================================================================================

    public ChatResponse execute(AskContext c, ConversationSession session,
                                String mode, Integer freeRemaining, long start) {
        return execute(c, session, mode, freeRemaining, start, null);
    }

    public ChatResponse execute(AskContext c, ConversationSession session,
                                String mode, Integer freeRemaining, long start,
                                AgentStreamHooks hooks) {
        String question = c.getQuestion();
        boolean deepTrace = agentProps.isDeepTraceRequest(question, c.getIntent());
        int plannedBudget = agentProps.budgetFor(mode, deepTrace);
        int activeBudget = plannedBudget;
        int graceTurns = agentProps.graceTurnsFor(deepTrace);
        boolean graceGranted = false;
        AgentHarvest h = new AgentHarvest();
        if (c.getSpecifiedProjects() != null && !c.getSpecifiedProjects().isEmpty()) {
            h.specifiedProjects = c.getSpecifiedProjects().stream()
                    .filter(k -> loader.findByName(k) != null)
                    .toList();
        }
        Map<String, Boolean> availability = computeAvailability();
        List<ChatResponse.Step> steps = new ArrayList<>();
        java.util.function.Consumer<ChatResponse.Step> emit = hooks == null ? null : hooks.onStep();
        RuntimeMetrics metrics = new RuntimeMetrics();

        // FAQ 标题精确覆盖用户问题时，直接把完整章节作为唯一材料进入成文轮。
        // 这类“线上问题处理”不需要让模型自由选择工具，更不应扫描 44 个代码仓库。
        boolean exactFaqHit = false;
        if ("user".equals(mode) && !deepTrace && isDirectFaqQuestion(question)) {
            long faqStarted = System.currentTimeMillis();
            exactFaqHit = prefetchExactFaqSection(question, h);
            metrics.toolCalls++;
            metrics.toolMs += System.currentTimeMillis() - faqStarted;
        }
        if (exactFaqHit) {
            addStep(steps, emit, ChatResponse.Step.builder().no(1).phase("tool_call")
                    .tool("search_docs").args("{\"query\":\"" + jsonEscape(question) + "\",\"match\":\"exact_heading\"}")
                    .status("ok").elapsedMs(0).build());
            addStep(steps, emit, ChatResponse.Step.builder().no(1).phase("tool_result")
                    .tool("search_docs").summary("FAQ 标题精确命中，直接按文档回答")
                    .status("ok").elapsedMs(0).build());
        }

        // ---- 探索循环 ----
        List<ToolChatMessage> messages = new ArrayList<>();
        messages.add(ToolChatMessage.system(buildExploreSystemPrompt(
                mode, availability, plannedBudget, deepTrace, graceTurns)
                + buildTaskSpecificExploreRules(c)));
        String initialPrompt = buildInitialUserPrompt(question, session, c);
        Map<String, Integer> failStreak = new HashMap<>();
        boolean gatewayEvidenceReady = false;
        if (!exactFaqHit) {
            String preloaded = prefetchTargetedEntry(c, availability, h, failStreak, steps, emit, metrics);
            if (c.isTargetedGatewayLookup()) {
                String gatewayEvidence = prefetchGatewayRouteEvidence(c, availability, h, failStreak,
                        steps, emit, metrics);
                if (!gatewayEvidence.isBlank()) {
                    preloaded += gatewayEvidence;
                    gatewayEvidenceReady = hasCompleteGatewayRouteEvidence(
                            gatewayEvidence, c.detectedApiPath(), gatewayTarget(c.getQuestion()));
                }
            }
            if (!preloaded.isBlank()) initialPrompt += preloaded;
        }
        messages.add(ToolChatMessage.user(initialPrompt));

        List<Map<String, Object>> tools = toolSchemas();
        int turn = exactFaqHit || gatewayEvidenceReady ? 1 : 0;
        int usedTokens = 0;
        boolean modelDone = exactFaqHit || gatewayEvidenceReady;
        boolean tokenGateHit = false;
        String inlineAnswer = exactFaqHit ? renderExactFaqAnswer(h) + "\n\n[CONFIDENCE:high]" : null;
        if (gatewayEvidenceReady) {
            addStep(steps, emit, ChatResponse.Step.builder().no(1).phase("closure_check")
                    .tool("gateway_route")
                    .summary("已验证精确 URL 命中与网关 route/upstream，直接进入最终成文")
                    .status("ok").elapsedMs(0).build());
            log.info("精确网关路由证据闭环，跳过决策循环 path={}", c.detectedApiPath());
        }

        while (turn < activeBudget && !modelDone) {
            checkCancelled();
            turn++;
            ToolChatResult r;
            long decisionStarted = System.currentTimeMillis();
            metrics.decisionCalls++;
            try {
                r = llm.chatWithTools(messages, tools, c.getRt());
            } catch (Exception e) {
                metrics.decisionMs += System.currentTimeMillis() - decisionStarted;
                log.warn("agent 决策轮失败（turn={}），重试一次: {}", turn, e.getMessage());
                decisionStarted = System.currentTimeMillis();
                metrics.decisionCalls++;
                try {
                    r = llm.chatWithTools(messages, tools, c.getRt());
                } catch (Exception e2) {
                    metrics.decisionMs += System.currentTimeMillis() - decisionStarted;
                    log.warn("agent 决策轮重试仍失败，进入软着陆成文: {}", e2.getMessage());
                    break;
                }
            }
            metrics.decisionMs += System.currentTimeMillis() - decisionStarted;
            if (r.getPromptTokens() != null) usedTokens += r.getPromptTokens();
            if (r.getCompletionTokens() != null) usedTokens += r.getCompletionTokens();

            List<ToolCall> calls = r.getToolCalls() == null ? List.of() : r.getToolCalls();
            if (calls.isEmpty()) {
                modelDone = true;
                if (r.getContent() != null && r.getContent().contains("[CONFIDENCE:")) {
                    inlineAnswer = r.getContent();   // 模型已直接给出完整回答
                }
                break;
            }

            // assistant 消息原样回填（含 tool_calls），再逐个执行工具
            messages.add(ToolChatMessage.builder().role("assistant")
                    .content(r.getContent() == null ? "" : r.getContent())
                    .toolCalls(calls).build());

            boolean successfulToolThisTurn = false;
            for (ToolCall call : calls) {
                checkCancelled();
                long t0 = System.currentTimeMillis();
                String argsPretty = prettyJson(call.arguments());
                addStep(steps, emit, ChatResponse.Step.builder().no(turn).phase("tool_call")
                        .tool(call.name()).args(argsPretty).status("ok").elapsedMs(0).build());

                Set<String> hitsBefore = "search_code".equals(call.name())
                        ? new LinkedHashSet<>(h.codeHits.keySet()) : Set.of();
                ToolExecResult er = executeTool(call, availability, h, failStreak);
                String autoEvidence = "";
                if (er.ok() && c.isTargetedConfigLookup() && "search_code".equals(call.name())) {
                    autoEvidence = autoReadTargetedHits(h, hitsBefore, call.arguments(), availability,
                            failStreak, steps, emit, metrics, turn);
                }
                successfulToolThisTurn |= er.ok();
                long cost = System.currentTimeMillis() - t0;
                metrics.toolCalls++;
                metrics.toolMs += cost;
                addStep(steps, emit, ChatResponse.Step.builder().no(turn).phase("tool_result")
                        .tool(call.name()).summary(er.summary()).elapsedMs(cost)
                        .status(er.ok() ? "ok" : "error").build());
                messages.add(ToolChatMessage.tool(call.id(), er.content() + autoEvidence));
            }
            compressIfNeeded(messages);
            // 精确配置题已经拿齐“入口 + 配置定义 + 业务分支”证据时，本地即可判定闭环。
            // 不再额外调用一次模型只为返回 [READY]；最终答案仍由成文模型生成。
            if (successfulToolThisTurn && c.isTargetedConfigLookup()
                    && hasCompleteTargetedConfigEvidence(h)) {
                modelDone = true;
                addStep(steps, emit, ChatResponse.Step.builder().no(turn).phase("closure_check")
                        .summary("已取得接口入口、配置定义和业务分支证据，进入最终成文")
                        .elapsedMs(0).status("ok").build());
                log.info("精确配置题证据闭环，跳过额外 READY 决策（turn={}, readFiles={}, materials={}）",
                        turn, h.readFiles.size(), h.materials.size());
                break;
            }
            if (usedTokens > agentProps.getTokenBudget()) {
                log.info("agent 触发 token 二道闸（{}），软着陆", usedTokens);
                tokenGateHit = true;
                break;
            }
            // 深度追踪在计划预算的最后一轮仍成功拿到新材料，说明链路很可能刚出现下一跳。
            // 只扩容一次，且始终受 HARD_CAP_BUDGET 限制；模型仍可随时主动结束。
            if (deepTrace && !graceGranted && successfulToolThisTurn && turn >= activeBudget && graceTurns > 0) {
                int extended = Math.min(activeBudget + graceTurns, AgentProperties.HARD_CAP_BUDGET);
                if (extended > activeBudget) {
                    graceGranted = true;
                    activeBudget = extended;
                    addStep(steps, emit, ChatResponse.Step.builder().no(turn).phase("budget_extension")
                            .summary("深度链路仍有新线索，追加 " + (extended - turn) + " 轮收尾预算")
                            .elapsedMs(0).status("ok").build());
                }
            }
        }
        boolean exhausted = !modelDone;
        String incompleteReason = null;
        if (exhausted) {
            String reason = tokenGateHit
                    ? "token 预算耗尽（累计 " + usedTokens + " > " + agentProps.getTokenBudget() + "），基于已有材料强制成文"
                    : "步数预算耗尽（" + activeBudget + "），调用链可能尚未闭环，基于已有材料强制成文";
            incompleteReason = reason;
            steps.add(ChatResponse.Step.builder().no(turn).phase("force_answer")
                    .summary(reason)
                    .elapsedMs(0).status("ok").build());
            if (emit != null) emit.accept(steps.get(steps.size() - 1));
        }

        // ---- 成文轮 ----
        String answer;
        String confidence;
        if (inlineAnswer != null) {
            String[] parsed = parseConfidence(inlineAnswer);
            answer = parsed[0];
            confidence = parsed[1];
        } else if (h.materials.isEmpty()) {
            answer = "没能在本地代码库和文档里找到与问题相关的材料。\n"
                    + "可以换个说法再问，或确认相关项目是否已克隆到 " + projectsProps.getCodeBase()
                    + "（标注【未下载】的项目需要先克隆）。";
            confidence = "low";
        } else {
            long composeStarted = System.currentTimeMillis();
            try {
                String system = buildFinalSystemPrompt(mode, new ArrayList<>(h.searchedProjects),
                        c.useFlowchart(), c.usesBriefAnswer());
                String user = buildFinalUserPrompt(question, session, h, mode, exhausted, incompleteReason);
                String resp = (hooks != null && hooks.onDelta() != null)
                        ? llm.chatStream(system, user, c.getRt(), hooks.onDelta())   // 打字机
                        : llm.chat(system, user, c.getRt());
                String[] parsed = parseConfidence(resp);
                answer = parsed[0];
                confidence = parsed[1];
            } catch (Exception e) {
                log.error("agent 成文轮失败", e);
                answer = "材料已收集完成，但生成回答时出错：" + e.getMessage();
                confidence = "low";
            } finally {
                metrics.composeMs += System.currentTimeMillis() - composeStarted;
            }
        }
        if (exhausted) {
            // 预算截断的回答不能以 high 进入语义缓存，否则会反复回放一份未闭环结论。
            if ("high".equalsIgnoreCase(confidence)) confidence = "medium";
            answer = "⚠️ 本次探索达到预算上限，调用链尚未确认闭环；下面是已经由代码验证的部分。\n\n" + answer;
        }
        answer = c.enforceOutputPreferences(answer);
        boolean needHuman = "low".equalsIgnoreCase(confidence);

        session.append(new ChatMessage("user", question));
        session.append(new ChatMessage("assistant", answer));

        long elapsed = System.currentTimeMillis() - start;
        log.info("=== agent 回答完成，耗时={}ms, turns={}/{}, deepTrace={}, 材料块={}, confidence={}, completion={} ===",
                elapsed, turn, activeBudget, deepTrace, h.materials.size(), confidence,
                exhausted ? "partial" : "complete");

        // ---- 组装响应 ----
        List<String> sourceFiles = new ArrayList<>();
        List<ChatResponse.SourceRef> sources = new ArrayList<>();
        // 实读文件最可靠：模型只精读它认为值得深入的文件，优先作为来源
        for (String rel : h.readFiles) {
            if (sourceFiles.contains(rel)) continue;
            if (sourceFiles.size() >= 12) break;
            sourceFiles.add(rel);
            String proj = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
            sources.add(ChatResponse.SourceRef.builder().project(proj).file(rel).build());
        }
        for (SearchResult sr : h.codeHits.values()) {
            String rel = toRelative(sr.getFilePath());
            if (sourceFiles.contains(rel)) continue;
            if (sourceFiles.size() >= 12) break;
            sourceFiles.add(rel);
            boolean entry = sr.getRouteAnnotationCount() > 0 || rel.endsWith("Controller.java");
            sources.add(ChatResponse.SourceRef.builder()
                    .project(sr.getProjectName()).file(rel)
                    .lines(sr.getHitLines() == null ? List.of()
                            : sr.getHitLines().subList(0, Math.min(10, sr.getHitLines().size())))
                    .keyword(sr.getMatchedKeyword()).entry(entry).build());
        }
        for (String doc : h.docHits.keySet()) {
            if (!sourceFiles.contains(doc) && sourceFiles.size() < 14) sourceFiles.add(doc);
        }

        return ChatResponse.builder()
                .answer(answer)
                .sessionId(session.getSessionId())
                .mode(mode)
                .matchedProjects(new ArrayList<>(h.searchedProjects))
                .matchedFeatures(List.of())
                .sourceFiles(sourceFiles)
                .sources(sources)
                .confidence(confidence)
                .needHuman(needHuman)
                .needKey(false)
                .freeRemaining(freeRemaining)
                .elapsedMs(elapsed)
                .engine("agent")
                .steps(steps)
                .stepsUsed(turn)
                .completionStatus(exhausted ? "partial" : "complete")
                .incompleteReason(incompleteReason)
                .performance(ChatResponse.Performance.builder()
                        .route(exactFaqHit ? "faq_exact" : "agent")
                        .totalMs(elapsed).decisionMs(metrics.decisionMs)
                        .toolMs(metrics.toolMs).composeMs(metrics.composeMs)
                        .decisionCalls(metrics.decisionCalls).toolCalls(metrics.toolCalls).build())
                .build();
    }

    /**
     * 精确配置定位的本地闭环判定。
     *
     * <p>这里只省掉“证据已齐后再问模型能否结束”的确认轮，不参与生成答案，
     * 也不会在缺少入口、配置定义或业务分支任一类材料时提前结束。</p>
     */
    static boolean hasCompleteTargetedConfigEvidence(AgentHarvest h) {
        if (h == null || h.readFiles.size() < 4 || h.materials.size() < 4) return false;

        boolean hasController = false;
        boolean hasConfigCarrier = false;
        int nonControllerFiles = 0;
        for (String file : h.readFiles) {
            String lower = file.toLowerCase();
            if (lower.endsWith("controller.java")) {
                hasController = true;
            } else {
                nonControllerFiles++;
            }
            if (lower.contains("config") || lower.contains("switcher")
                    || lower.contains("properties") || lower.contains("setting")) {
                hasConfigCarrier = true;
            }
        }
        if (!hasController || !hasConfigCarrier || nonControllerFiles < 2) return false;

        String evidence = String.join("\n", h.materials).toLowerCase();
        boolean hasDefinition = evidence.contains("dataid")
                || evidence.contains("@value")
                || evidence.contains("configurationproperties")
                || evidence.contains("switcherid")
                || evidence.contains("group =")
                || evidence.contains("group=");
        boolean hasBranchMeaning = evidence.contains("if (")
                || evidence.contains("if(")
                || evidence.contains("switch (")
                || evidence.contains("switch(")
                || evidence.contains(".filter(")
                || evidence.contains("status")
                || evidence.contains("enabled")
                || evidence.contains("disabled")
                || evidence.contains("off_shelve")
                || evidence.contains("normal");
        return hasDefinition && hasBranchMeaning;
    }

    /** 步骤入列 + 实时发射（SSE 钩子存在时） */
    private void addStep(List<ChatResponse.Step> steps,
                         java.util.function.Consumer<ChatResponse.Step> emit,
                         ChatResponse.Step st) {
        steps.add(st);
        if (emit != null) emit.accept(st);
    }

    @Override
    public void checkCancelled() {
        java.util.concurrent.atomic.AtomicBoolean t = LlmCancellation.token();
        if (t != null && t.get()) {
            throw new LlmCancelledException();
        }
    }

    /**
     * 对“指定项目 + 精确接口 + 配置定位”先执行确定性的入口搜索，并读取最可信的入口文件。
     * 这是额外提供证据，不减少后续工具能力；模型仍负责继续追到配置定义和分支用途。
     */
    private String prefetchTargetedEntry(AskContext c,
                                         Map<String, Boolean> availability,
                                         AgentHarvest h,
                                         Map<String, Integer> failStreak,
                                         List<ChatResponse.Step> steps,
                                         java.util.function.Consumer<ChatResponse.Step> emit,
                                         RuntimeMetrics metrics) {
        if (!c.isTargetedConfigLookup() || h.specifiedProjects.isEmpty()) return "";

        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        if (c.getApiPath() != null && !c.getApiPath().isBlank()) anchors.add(c.getApiPath().trim());
        Matcher matcher = API_PATH_IN_TEXT.matcher(c.getQuestion() == null ? "" : c.getQuestion());
        while (matcher.find() && anchors.size() < 4) anchors.add(matcher.group());
        if (anchors.isEmpty()) return "";

        RouteControllerCandidate deterministicEntry = findRouteController(c, anchors);
        if (deterministicEntry != null) {
            String rel = toRelative(deterministicEntry.path().toString());
            ObjectNode readArgs = mapper.createObjectNode();
            readArgs.put("file_path", rel);
            readArgs.put("start_line", Math.max(1, deterministicEntry.hitLine() - 50));
            readArgs.put("end_line", deterministicEntry.hitLine() + 170);
            long started = System.currentTimeMillis();
            ToolExecResult read = executeTool(new ToolCall("config-prefetch-entry", "read_file",
                    readArgs.toString()), availability, h, failStreak);
            long elapsed = System.currentTimeMillis() - started;
            metrics.toolCalls++;
            metrics.toolMs += elapsed;
            addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                    .tool("resolve_route_controller").args(String.join(",", anchors)).summary(read.summary())
                    .elapsedMs(elapsed).status(read.ok() ? "ok" : "error").build());
            if (read.ok()) {
                return "\n\n系统已按 Spring 类级＋方法级路由确定性定位并读取入口（不要重复搜索 URL）：\n"
                        + "【" + rel + "】\n" + read.content()
                        + "\n请直接从入口中的真实调用继续定位配置定义、读取点和分支作用。";
            }
        }

        ObjectNode searchArgs = mapper.createObjectNode();
        ArrayNode keywords = searchArgs.putArray("keywords");
        anchors.stream().limit(8).forEach(keywords::add);
        ArrayNode projects = searchArgs.putArray("project_keys");
        h.specifiedProjects.stream().limit(6).forEach(projects::add);

        long searchStarted = System.currentTimeMillis();
        ToolExecResult searched = executeTool(new ToolCall("config-prefetch-search", "search_code",
                searchArgs.toString()), availability, h, failStreak);
        long searchElapsed = System.currentTimeMillis() - searchStarted;
        metrics.toolCalls++;
        metrics.toolMs += searchElapsed;
        addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                .tool("search_code").args(searchArgs.toString()).summary(searched.summary())
                .elapsedMs(searchElapsed).status(searched.ok() ? "ok" : "error").build());
        if (!searched.ok() || h.codeHits.isEmpty()) return "";

        // 完整 URL 常同时存在于 API/RPC 声明里，而 Spring Controller 往往拆成类级 + 方法级路径。
        // 再用末两段和业务段做一次本地窄搜，避免把 API 声明或同前缀 Controller 当作实现入口。
        ToolExecResult narrowed = null;
        LinkedHashSet<String> routeParts = new LinkedHashSet<>();
        for (String path : anchors) {
            String[] segments = path.replaceFirst("^/", "").split("/");
            if (segments.length >= 2) {
                routeParts.add("/" + segments[segments.length - 2] + "/" + segments[segments.length - 1]);
                routeParts.add(segments[segments.length - 2]);
            }
        }
        if (!routeParts.isEmpty()) {
            ObjectNode narrowArgs = mapper.createObjectNode();
            ArrayNode narrowKeywords = narrowArgs.putArray("keywords");
            routeParts.stream().limit(6).forEach(narrowKeywords::add);
            ArrayNode narrowProjects = narrowArgs.putArray("project_keys");
            h.specifiedProjects.stream().limit(6).forEach(narrowProjects::add);
            long narrowStarted = System.currentTimeMillis();
            narrowed = executeTool(new ToolCall("config-prefetch-narrow", "search_code",
                    narrowArgs.toString()), availability, h, failStreak);
            long narrowElapsed = System.currentTimeMillis() - narrowStarted;
            metrics.toolCalls++;
            metrics.toolMs += narrowElapsed;
            addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                    .tool("search_code").args(narrowArgs.toString()).summary(narrowed.summary())
                    .elapsedMs(narrowElapsed).status(narrowed.ok() ? "ok" : "error").build());
        }

        SearchResult entry = h.codeHits.values().stream()
                .filter(hit -> hit.isPathHit() || hit.getPathScore() >= 60 || hit.getRouteAnnotationCount() > 0)
                .max(Comparator.comparingInt(hit -> targetedEntryScore(hit, anchors)))
                .orElse(h.codeHits.values().iterator().next());
        String rel = toRelative(entry.getFilePath());
        int firstHit = entry.getHitLines() == null || entry.getHitLines().isEmpty()
                ? 1 : entry.getHitLines().get(0);
        ObjectNode readArgs = mapper.createObjectNode();
        readArgs.put("file_path", rel);
        readArgs.put("start_line", Math.max(1, firstHit - 40));
        readArgs.put("end_line", Math.min(entry.getTotalLines(), firstHit + 140));

        long readStarted = System.currentTimeMillis();
        ToolExecResult read = executeTool(new ToolCall("config-prefetch-read", "read_file",
                readArgs.toString()), availability, h, failStreak);
        long readElapsed = System.currentTimeMillis() - readStarted;
        metrics.toolCalls++;
        metrics.toolMs += readElapsed;
        addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                .tool("read_file").args(readArgs.toString()).summary(read.summary())
                .elapsedMs(readElapsed).status(read.ok() ? "ok" : "error").build());

        StringBuilder out = new StringBuilder("\n\n系统确定性预检（已经执行，不要重复调用）：\n")
                .append("search_code 结果：\n").append(searched.content());
        if (narrowed != null && narrowed.ok()) {
            out.append("\n实现入口窄搜结果：\n").append(narrowed.content());
        }
        if (read.ok()) {
            out.append("\n入口候选源码 ").append(rel).append("：\n").append(read.content());
        }
        out.append("\n请从这些已验证材料继续定位配置定义、读取点和分支作用。");
        return out.toString();
    }

    /** 精确网关题先由宿主搜索 URL 并实读 APISIX/nginx 候选，避免多轮模型重复试关键词。 */
    private String prefetchGatewayRouteEvidence(AskContext c,
                                                Map<String, Boolean> availability,
                                                AgentHarvest h,
                                                Map<String, Integer> failStreak,
                                                List<ChatResponse.Step> steps,
                                                java.util.function.Consumer<ChatResponse.Step> emit,
                                                RuntimeMetrics metrics) {
        String apiPath = c.detectedApiPath();
        if (apiPath == null || apiPath.isBlank()) return "";
        ObjectNode searchArgs = mapper.createObjectNode();
        searchArgs.putArray("keywords").add(apiPath);
        searchArgs.putArray("project_keys");

        long searchStarted = System.currentTimeMillis();
        ToolExecResult searched = executeTool(new ToolCall("gateway-prefetch-search", "search_code",
                searchArgs.toString()), availability, h, failStreak);
        long searchElapsed = System.currentTimeMillis() - searchStarted;
        metrics.toolCalls++;
        metrics.toolMs += searchElapsed;
        addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                .tool("search_code").args(searchArgs.toString()).summary(searched.summary())
                .elapsedMs(searchElapsed).status(searched.ok() ? "ok" : "error").build());
        if (!searched.ok()) return "";

        StringBuilder out = new StringBuilder("\n\n系统已执行精确网关路由预检（不要重复搜索同一 URL）：\n")
                .append(searched.content()).append('\n');
        h.materials.add("【gateway_route_search " + apiPath + "】\n" + searched.content());

        // search_code 会同时匹配 /api、方法名等低权重变体，大文件的 hitLineSamples 未必包含
        // 完整 URL。对 pathHit 的网关文件再做一次精确文件内搜索，保证“该 URL 确实登记”有行号证据。
        List<Map.Entry<String, SearchResult>> exactFiles = h.codeHits.entrySet().stream()
                .filter(entry -> entry.getValue().isPathHit() && isGatewayProject(entry.getValue()))
                .limit(3).toList();
        int searchIndex = 0;
        for (Map.Entry<String, SearchResult> exact : exactFiles) {
            ObjectNode exactArgs = mapper.createObjectNode();
            exactArgs.put("file_path", exact.getKey());
            exactArgs.putArray("keywords").add(apiPath);
            long started = System.currentTimeMillis();
            ToolExecResult located = executeTool(new ToolCall("gateway-exact-" + (++searchIndex),
                    "search_in_file", exactArgs.toString()), availability, h, failStreak);
            long elapsed = System.currentTimeMillis() - started;
            metrics.toolCalls++;
            metrics.toolMs += elapsed;
            addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                    .tool("search_in_file").args(exactArgs.toString()).summary(located.summary())
                    .elapsedMs(elapsed).status(located.ok() ? "ok" : "error").build());
            if (located.ok()) out.append("\n【精确 URL @ ").append(exact.getKey())
                    .append("】\n").append(located.content());
        }

        String target = gatewayTarget(c.getQuestion());
        List<String> gatewayProjects = loader.getProjects().stream()
                .map(ProjectInfo::getName)
                .filter(name -> {
                    String lower = name == null ? "" : name.toLowerCase();
                    return availability.getOrDefault(name, false)
                            && (lower.contains("apisix") || lower.contains("gateway"));
                }).toList();
        if (!target.isBlank() && !gatewayProjects.isEmpty()) {
            ObjectNode routeArgs = mapper.createObjectNode();
            ArrayNode routeKeywords = routeArgs.putArray("keywords");
            routeKeywords.add("upstream-" + target.toLowerCase());
            routeKeywords.add(target);
            routeKeywords.add("proxy_pass");
            ArrayNode routeProjects = routeArgs.putArray("project_keys");
            gatewayProjects.forEach(routeProjects::add);
            long routeStarted = System.currentTimeMillis();
            ToolExecResult routeSearch = executeTool(new ToolCall("gateway-target-search", "search_code",
                    routeArgs.toString()), availability, h, failStreak);
            long routeElapsed = System.currentTimeMillis() - routeStarted;
            metrics.toolCalls++;
            metrics.toolMs += routeElapsed;
            addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                    .tool("search_code").args(routeArgs.toString()).summary(routeSearch.summary())
                    .elapsedMs(routeElapsed).status(routeSearch.ok() ? "ok" : "error").build());
            if (routeSearch.ok()) {
                out.append("\n【目标 upstream 搜索】\n").append(routeSearch.content());
                h.materials.add("【gateway_target_search】\n" + routeSearch.content());
            }
        }

        List<Map.Entry<String, SearchResult>> candidates = h.codeHits.entrySet().stream()
                .filter(entry -> isGatewayProject(entry.getValue()))
                .sorted((a, b) -> Integer.compare(gatewayEvidenceScore(b.getValue()),
                        gatewayEvidenceScore(a.getValue())))
                .limit(6)
                .toList();
        int index = 0;
        for (Map.Entry<String, SearchResult> candidate : candidates) {
            SearchResult hit = candidate.getValue();
            int firstHit = hit.getHitLines() == null || hit.getHitLines().isEmpty()
                    ? 1 : hit.getHitLines().get(0);
            ObjectNode readArgs = mapper.createObjectNode();
            readArgs.put("file_path", candidate.getKey());
            readArgs.put("start_line", Math.max(1, firstHit - 80));
            readArgs.put("end_line", Math.min(hit.getTotalLines(), firstHit + 180));
            long readStarted = System.currentTimeMillis();
            ToolExecResult read = executeTool(new ToolCall("gateway-prefetch-read-" + (++index),
                    "read_file", readArgs.toString()), availability, h, failStreak);
            long elapsed = System.currentTimeMillis() - readStarted;
            metrics.toolCalls++;
            metrics.toolMs += elapsed;
            addStep(steps, emit, ChatResponse.Step.builder().no(0).phase("prefetch")
                    .tool("read_file").args(readArgs.toString()).summary(read.summary())
                    .elapsedMs(elapsed).status(read.ok() ? "ok" : "error").build());
            if (read.ok()) out.append("\n【").append(candidate.getKey()).append("】\n").append(read.content());
        }
        return out.append("\n请只根据已验证的 route/location 与 upstream 给出结论和证据。\n").toString();
    }

    private static int gatewayEvidenceScore(SearchResult result) {
        int score = result.isPathHit() ? 2_000 : result.isPartialPathHit() ? 1_000 : 0;
        String path = result.getFilePath() == null ? "" : result.getFilePath().toLowerCase();
        if (path.endsWith(".conf") || path.contains("route") || path.contains("vhost")) score += 800;
        String keyword = result.getMatchedKeyword() == null ? "" : result.getMatchedKeyword().toLowerCase();
        if (keyword.startsWith("upstream-") || keyword.contains("proxy_pass")) score += 1_200;
        score += Math.min(20, result.getHitCount());
        return score;
    }

    private static boolean hasCompleteGatewayRouteEvidence(String evidence, String apiPath, String target) {
        String normalized = evidence == null ? "" : evidence.toLowerCase();
        boolean exactGatewayHit = apiPath != null && normalized.contains(apiPath.toLowerCase());
        boolean hasRoute = normalized.contains("location") || normalized.contains("route")
                || normalized.contains("uris");
        boolean hasTarget = normalized.contains("upstream") || normalized.contains("proxy_pass")
                || normalized.contains("service_name") || normalized.contains("nodes");
        boolean namedTarget = target != null && !target.isBlank()
                && normalized.contains(target.toLowerCase());
        return exactGatewayHit && hasRoute && hasTarget && namedTarget;
    }

    private static boolean isGatewayProject(SearchResult hit) {
        String project = hit.getProjectName() == null ? "" : hit.getProjectName().toLowerCase();
        return project.contains("apisix") || project.contains("gateway");
    }

    static String gatewayTarget(String question) {
        if (question == null || question.isBlank()) return "";
        Matcher matcher = Pattern.compile("(?i)(?:转发到|转到|走|到)\\s*([A-Za-z][A-Za-z0-9_-]{1,30})")
                .matcher(question);
        if (!matcher.find()) return "";
        String target = matcher.group(1);
        return Set.of("api", "apisix", "http", "https", "nginx", "gateway")
                .contains(target.toLowerCase()) ? "" : target;
    }

    /**
     * 不依赖 LLM 或 TopN 排序，直接在指定项目的 Controller 源码中组合匹配 Spring 路由。
     * 完整 URL 经常被拆在类级 @RequestMapping 与方法级 @GetMapping 中，全文检索会把 API 声明排在前面。
     */
    private RouteControllerCandidate findRouteController(AskContext c, Set<String> anchors) {
        RouteControllerCandidate best = null;
        for (String projectKey : c.getSpecifiedProjects()) {
            ProjectInfo project = loader.findByName(projectKey);
            if (project == null) continue;
            Path root = Paths.get(project.resolveAbsolutePath(projectsProps.getCodeBase()))
                    .toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) continue;
            List<Path> controllers;
            try (Stream<Path> stream = Files.walk(root)) {
                controllers = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                        .toList();
            } catch (Exception e) {
                log.debug("确定性路由入口扫描失败 project={}: {}", projectKey, e.getMessage());
                continue;
            }
            for (Path controller : controllers) {
                List<String> lines;
                try {
                    lines = searcher.readLines(controller);
                } catch (Exception ignored) {
                    continue;
                }
                String content = String.join("\n", lines).toLowerCase();
                String fileName = controller.getFileName().toString().toLowerCase();
                for (String anchor : anchors) {
                    String[] segments = anchor.replaceFirst("^/", "").toLowerCase().split("/");
                    if (segments.length < 2) continue;
                    String action = segments[segments.length - 1];
                    String resource = segments[segments.length - 2];
                    String resourceStem = resource.endsWith("ing") && resource.length() > 6
                            ? resource.substring(0, resource.length() - 3) : resource;
                    if (!content.contains(resource) || !content.contains(action)) continue;
                    int score = 0;
                    if (content.contains(anchor.toLowerCase())) score += 10000;
                    if (fileName.contains(resource) || fileName.contains(resourceStem)) score += 5000;
                    if (content.contains("/" + resource + "/" + action)) score += 3000;
                    if (content.contains("\"" + action + "\"") || content.contains("\"/" + action + "\"")) score += 1000;
                    int hitLine = 1;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).toLowerCase();
                        if (line.contains(action) && (line.contains("mapping") || line.contains("path"))) {
                            hitLine = i + 1;
                            break;
                        }
                    }
                    if (best == null || score > best.score()) {
                        best = new RouteControllerCandidate(controller, hitLine, score);
                    }
                }
            }
        }
        return best;
    }

    /**
     * 配置定位题中，search_code 已经返回精确候选后直接批量精读 Top 文件，
     * 避免再花一个远程模型往返来逐个下发 read_file。读取仍受原有路径和行数安全限制。
     */
    private String autoReadTargetedHits(AgentHarvest h,
                                        Set<String> hitsBefore,
                                        String rawArguments,
                                        Map<String, Boolean> availability,
                                        Map<String, Integer> failStreak,
                                        List<ChatResponse.Step> steps,
                                        java.util.function.Consumer<ChatResponse.Step> emit,
                                        RuntimeMetrics metrics,
                                        int turn) {
        LinkedHashSet<String> exactTypeFiles = new LinkedHashSet<>();
        boolean pathLookup = false;
        try {
            JsonNode args = mapper.readTree(rawArguments == null ? "{}" : rawArguments);
            for (JsonNode keywordNode : args.path("keywords")) {
                String keyword = keywordNode.asText("").trim();
                if (keyword.startsWith("/")) pathLookup = true;
                String symbol = keyword.replaceFirst("^(?i:class|interface|enum)\\s+", "").trim();
                if (symbol.matches("[A-Z][A-Za-z0-9_$]*(?:Biz|Service|Controller|Config|Bean|Enum|Manager|Facade|Rpc|Client|Status|Switcher|Constant|Constants)")) {
                    exactTypeFiles.add(symbol + ".java");
                }
            }
        } catch (Exception ignored) {
            return "";
        }

        List<Map.Entry<String, SearchResult>> unread = h.codeHits.entrySet().stream()
                .filter(entry -> !h.readFiles.contains(entry.getKey()))
                .toList();
        List<Map.Entry<String, SearchResult>> candidates;
        if (!exactTypeFiles.isEmpty()) {
            // 精确类型可能在更早一次宽搜中已进入 codeHits；只要尚未实读就直接读取，
            // 不要求它必须是本轮新增命中。
            candidates = unread.stream()
                    .filter(entry -> exactTypeFiles.stream().anyMatch(entry.getKey()::endsWith))
                    .limit(3).toList();
        } else if (pathLookup) {
            candidates = unread.stream()
                    .filter(entry -> !hitsBefore.contains(entry.getKey()))
                    .filter(entry -> isServiceController(entry.getKey()))
                    .sorted((a, b) -> Integer.compare(targetedEntryScore(b.getValue()), targetedEntryScore(a.getValue())))
                    .limit(1).toList();
        } else {
            // 普通业务词可能命中大量相邻类，自动读取反而会放大上下文；交给模型先收窄。
            candidates = List.of();
        }
        if (candidates.isEmpty()) return "";

        StringBuilder appended = new StringBuilder("\n\n系统批量精读的新候选（已经执行，不要重复读取）：\n");
        for (Map.Entry<String, SearchResult> candidate : candidates) {
            SearchResult hit = candidate.getValue();
            int firstHit = hit.getHitLines() == null || hit.getHitLines().isEmpty()
                    ? 1 : hit.getHitLines().get(0);
            ObjectNode readArgs = mapper.createObjectNode();
            readArgs.put("file_path", candidate.getKey());
            readArgs.put("start_line", Math.max(1, firstHit - 40));
            readArgs.put("end_line", Math.min(hit.getTotalLines(), firstHit + 120));
            long started = System.currentTimeMillis();
            ToolExecResult read = executeTool(new ToolCall("targeted-auto-read-" + turn,
                    "read_file", readArgs.toString()), availability, h, failStreak);
            long elapsed = System.currentTimeMillis() - started;
            metrics.toolCalls++;
            addStep(steps, emit, ChatResponse.Step.builder().no(turn).phase("auto_read")
                    .tool("read_file").args(readArgs.toString()).summary(read.summary())
                    .elapsedMs(elapsed).status(read.ok() ? "ok" : "error").build());
            if (read.ok()) {
                appended.append("\n【").append(candidate.getKey()).append("】\n").append(read.content());
            }
        }
        return appended.toString();
    }

    private int targetedEntryScore(SearchResult hit) {
        String path = toRelative(hit.getFilePath());
        int score = hit.getPathScore() * 10 + hit.getRouteAnnotationCount();
        if (isServiceController(path)) score += 2000;
        else if (path.contains("/service/") || path.contains("-service/")) score += 500;
        if (path.contains("-api/") || path.contains("/api/")) score -= 300;
        return score;
    }

    private int targetedEntryScore(SearchResult hit, Set<String> routeAnchors) {
        int score = targetedEntryScore(hit);
        String fileName = Paths.get(hit.getFilePath()).getFileName().toString().toLowerCase();
        for (String route : routeAnchors) {
            String[] segments = route.replaceFirst("^/", "").toLowerCase().split("/");
            for (String segment : segments) {
                if (segment.length() < 4) continue;
                String stem = segment.endsWith("ing") && segment.length() > 6
                        ? segment.substring(0, segment.length() - 3) : segment;
                if (fileName.contains(segment) || fileName.contains(stem)) score += 10000;
            }
        }
        return score;
    }

    private boolean isServiceController(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        return normalized.contains("/service/") && normalized.contains("/controller/")
                && normalized.endsWith("Controller.java");
    }

    static void applyDefaultProjectScope(List<String> requestedKeys, AgentHarvest h) {
        if (requestedKeys.isEmpty() && h != null && !h.specifiedProjects.isEmpty()) {
            requestedKeys.addAll(h.specifiedProjects);
        }
    }

    // ==================================================================================
    // 工具执行
    // ==================================================================================

    @Override
    public ToolExecResult executeTool(ToolCall call, Map<String, Boolean> availability,
                                     AgentHarvest h, Map<String, Integer> failStreak) {
        try {
            return toolExecutionGuard.execute(call.name(), agentProps.getToolTimeoutMs(),
                    () -> executeToolInternal(call, availability, h, failStreak));
        } catch (ToolExecutionGuard.ToolTimeoutException timeout) {
            String key = call.name() + "|" + call.arguments();
            failStreak.merge(key, 1, Integer::sum);
            return err("TOOL_TIMEOUT", "工具执行超过 " + timeout.timeoutMs()
                    + "ms，已中断；请缩小项目范围/关键词或改走精确文件读取");
        }
    }

    private ToolExecResult executeToolInternal(ToolCall call, Map<String, Boolean> availability,
                                               AgentHarvest h, Map<String, Integer> failStreak) {
        String key = call.name() + "|" + call.arguments();
        try {
            JsonNode args = mapper.readTree(call.arguments() == null ? "{}" : call.arguments());
            ToolExecResult r = switch (call.name() == null ? "" : call.name()) {
                case "get_project_info" -> toolGetProjectInfo(args, availability);
                case "search_code" -> toolSearchCode(args, availability, h);
                case "read_file" -> toolReadFile(args, h);
                case "search_in_file" -> toolSearchInFile(args, h);
                case "search_docs" -> toolSearchDocs(args, h);
                case "semantic_search" -> toolSemanticSearch(args, h);
                default -> err("INVALID_ARGS", "未知工具: " + call.name());
            };
            if (r.ok()) {
                failStreak.remove(key);
            } else {
                int n = failStreak.merge(key, 1, Integer::sum);
                if (n >= 2) {
                    return err("TOOL_FAILED", "该调用连续失败 2 次，请换关键词/换路径或直接基于已有材料作答");
                }
            }
            return r;
        } catch (Exception e) {
            log.warn("工具执行异常 {}: {}", call.name(), e.getMessage());
            int n = failStreak.merge(key, 1, Integer::sum);
            if (n >= 2) {
                return err("TOOL_FAILED", "该调用连续失败 2 次，请换路或直接作答");
            }
            return err("INVALID_ARGS", "参数解析失败: " + e.getMessage());
        }
    }

    private ToolExecResult toolGetProjectInfo(JsonNode args, Map<String, Boolean> availability) {
        String key = args.path("project_key").asText("");
        ProjectInfo p = loader.findByName(key);
        if (p == null) {
            return err("INVALID_ARGS", "未知项目 key=" + key + "，可用项目见系统提示里的项目目录");
        }
        boolean available = availability.getOrDefault(key, false);
        ObjectNode n = mapper.createObjectNode();
        n.put("name", p.getName());
        if (p.getDescription() != null) n.put("description", p.getDescription());
        putStrArr(n.putArray("tags"), p.getTags(), 8);
        if (p.getFeatures() != null) {
            ArrayNode fs = n.putArray("features");
            p.getFeatures().forEach(f -> fs.add(f.getName()));
        }
        putStrArr(n.putArray("rpc_to"), p.getRpcTo(), 20);
        putStrArr(n.putArray("called_by"), p.getCalledBy(), 20);
        n.put("available", available);
        if (!available) {
            n.put("error", "PROJECT_NOT_LOCAL");
            n.put("message", p.getName() + " 未在本地 code-base，请先克隆到 "
                    + projectsProps.getCodeBase() + "/" + (p.getPath() == null ? p.getName() : p.getPath()));
        }
        return new ToolExecResult(true, n.toString(),
                p.getName() + " 元数据（" + (available ? "可用" : "未下载") + "）");
    }

    private ToolExecResult toolSearchCode(JsonNode args, Map<String, Boolean> availability, AgentHarvest h) {
        List<String> keywords = new ArrayList<>();
        args.path("keywords").forEach(k -> {
            String s = k.asText("").trim();
            if (!s.isEmpty() && keywords.size() < 8) keywords.add(s);
        });
        if (keywords.isEmpty()) {
            return err("INVALID_ARGS", "keywords 不能为空（1~8 个，OR 关系）");
        }
        List<String> keys = new ArrayList<>();
        args.path("project_keys").forEach(k -> {
            String s = k.asText("").trim();
            if (!s.isEmpty() && !keys.contains(s)) keys.add(s);
        });
        final List<String> effKeys;
        List<ProjectInfo> targets = new ArrayList<>();
        List<String> notLocal = new ArrayList<>();
        // 用户明确限定项目时，省略 project_keys 始终解释为“在指定项目内继续搜”。
        // 跨服务探索仍然开放，但必须根据已读证据显式写出提供方 project_keys，避免模型
        // 在简单配置题中无意触发全库扫描，并把 44 个项目的噪声带进后续上下文。
        applyDefaultProjectScope(keys, h);
        final boolean allProjects;
        if (keys.isEmpty()) {
            // 全库搜：URL 路径/注解/类名这类全局唯一符号，无需猜项目（裸 URL 猜项目极易走错方向）
            allProjects = true;
            effKeys = List.of("全部本地项目");
            for (ProjectInfo p : loader.getProjects()) {
                if (availability.getOrDefault(p.getName(), false)) targets.add(p);
            }
        } else {
            allProjects = false;
            effKeys = keys.size() > 6 ? new ArrayList<>(keys.subList(0, 6)) : keys;
            // 校验项目存在性 + 可用性（不可用的结构化提示，可用的进检索）
            for (String k : effKeys) {
                ProjectInfo p = loader.findByName(k);
                if (p == null) {
                    return err("INVALID_ARGS", "未知项目 key=" + k + "，可用项目见系统提示里的项目目录");
                }
                if (availability.getOrDefault(k, false)) {
                    targets.add(p);
                    h.searchedProjects.add(k);
                } else {
                    notLocal.add(k);
                }
            }
        }

        List<SearchResult> hits = targets.isEmpty() ? List.of() : searcher.search(targets, keywords);
        // 全库搜时 matchedProjects 只登记真正命中的项目，避免把十几个项目全列进回答
        if (allProjects) hits.forEach(sr -> h.searchedProjects.add(sr.getProjectName()));

        ObjectNode n = mapper.createObjectNode();
        ArrayNode arr = n.putArray("results");
        int shown = 0;
        for (SearchResult sr : hits) {
            if (shown++ >= 10) break;
            String rel = toRelative(sr.getFilePath());
            h.codeHits.put(rel, sr);
            ObjectNode one = arr.addObject();
            one.put("project", sr.getProjectName());
            one.put("file", rel);
            one.put("matchedKeyword", sr.getMatchedKeyword());
            one.put("hitCount", sr.getHitCount());
            one.put("pathHit", sr.isPathHit());
            if (sr.getRouteAnnotationCount() > 0) one.put("routeAnnotations", sr.getRouteAnnotationCount());
            one.put("totalLines", sr.getTotalLines());
            // 命中行内容摘要（最多 8 行，每行截断）：简单问题可不再 read_file
            ArrayNode lines = one.putArray("hitLineSamples");
            appendHitLines(sr, lines);
        }
        if (!notLocal.isEmpty()) {
            ArrayNode nl = n.putArray("notLocalProjects");
            notLocal.forEach(x -> {
                ObjectNode o = nl.addObject();
                o.put("project", x);
                o.put("error", "PROJECT_NOT_LOCAL");
                o.put("message", x + " 未在本地，请提示用户克隆到 " + projectsProps.getCodeBase() + "/" + x);
            });
        }
        if (hits.isEmpty() && notLocal.isEmpty()) {
            n.put("note", "无命中。可换关键词（中文业务词 → 类名/方法名/英文单词/拼音驼峰）或扩大项目范围重试");
        }

        // 材料块（成文用）
        StringBuilder mat = new StringBuilder("【search_code " + keywords + " @ " + effKeys + "】\n");
        int i = 0;
        for (SearchResult sr : hits) {
            if (i++ >= 6) break;
            mat.append("- ").append(toRelative(sr.getFilePath()))
                    .append(" (kw=").append(sr.getMatchedKeyword())
                    .append(" ×").append(sr.getHitCount()).append(")\n");
        }
        if (!hits.isEmpty()) h.materials.add(mat.toString());

        String summary = (targets.isEmpty() ? "" : String.join(",", targets.stream().map(ProjectInfo::getName).toList()) + " ")
                + "命中 " + hits.size() + " 文件"
                + (notLocal.isEmpty() ? "" : "；" + notLocal.size() + " 个项目未下载");
        return new ToolExecResult(true, n.toString(), summary);
    }

    private ToolExecResult toolReadFile(JsonNode args, AgentHarvest h) {
        String rel = args.path("file_path").asText("").trim().replace('\\', '/');
        if (rel.isEmpty()) {
            return err("INVALID_ARGS", "file_path 不能为空（用 search_code 结果里的 file 字段原样传入）");
        }
        Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
        Path p = base.resolve(rel).toAbsolutePath().normalize();
        if (!p.startsWith(base)) {
            return err("INVALID_PATH", "路径越界：只允许访问 code-base 内的文件");
        }
        if (!Files.isRegularFile(p)) {
            return err("INVALID_ARGS", "文件不存在: " + rel + "（请用 search_code 结果里的路径）");
        }
        String name = p.getFileName().toString();
        if (searchProps.getExcludeExts().stream().anyMatch(name::endsWith)) {
            return err("INVALID_ARGS", "不支持的文件类型: " + name);
        }
        int maxLines = searchProps.getMaxLinesPerFile();
        int start = Math.max(1, args.path("start_line").asInt(1));
        int end = args.path("end_line").asInt(start + maxLines - 1);
        if (end - start + 1 > maxLines) end = start + maxLines - 1;

        List<String> allLines;
        try {
            allLines = searcher.readLines(p);
        } catch (Exception e) {
            return err("INVALID_ARGS", "读取失败（可能不是 UTF-8 文本）: " + rel);
        }
        int from = Math.min(start, Math.max(1, allLines.size()));
        int to = Math.min(end, allLines.size());
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i).append('|').append(allLines.get(i - 1)).append('\n');
        }
        String content = sb.length() > agentProps.getToolResultCapBytes()
                ? sb.substring(0, agentProps.getToolResultCapBytes()) + "\n...(截断)"
                : sb.toString();
        h.readFiles.add(rel);
        h.materials.add("【read_file " + rel + " " + from + "-" + to + " 行】\n" + content);
        return new ToolExecResult(true, content, "读取 " + rel + " " + from + "-" + to + " 行");
    }

    /** 单文件内 grep：返回所有命中行 + 行号（±2 行上下文），专治大文件里定位方法/调用点 */
    private ToolExecResult toolSearchInFile(JsonNode args, AgentHarvest h) {
        String rel = args.path("file_path").asText("").trim().replace('\\', '/');
        if (rel.isEmpty()) {
            return err("INVALID_ARGS", "file_path 不能为空（用 search_code 结果里的 file 字段原样传入）");
        }
        List<String> keywords = new ArrayList<>();
        args.path("keywords").forEach(k -> {
            String s = k.asText("").trim();
            if (!s.isEmpty() && keywords.size() < 6) keywords.add(s);
        });
        if (keywords.isEmpty()) {
            return err("INVALID_ARGS", "keywords 不能为空");
        }
        Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
        Path p = base.resolve(rel).toAbsolutePath().normalize();
        if (!p.startsWith(base)) {
            return err("INVALID_PATH", "路径越界：只允许访问 code-base 内的文件");
        }
        if (!Files.isRegularFile(p)) {
            return err("INVALID_ARGS", "文件不存在: " + rel);
        }
        List<String> allLines;
        try {
            allLines = searcher.readLines(p);
        } catch (Exception e) {
            return err("INVALID_ARGS", "读取失败（可能不是 UTF-8 文本）: " + rel);
        }
        int maxMatches = 40;
        StringBuilder sb = new StringBuilder("文件共 " + allLines.size() + " 行\n");
        int matched = 0;
        int lastPrinted = -10;
        for (int i = 0; i < allLines.size() && matched < maxMatches; i++) {
            String low = allLines.get(i).toLowerCase();
            boolean hit = false;
            for (String k : keywords) {
                if (low.contains(k.toLowerCase())) { hit = true; break; }
            }
            if (!hit) continue;
            matched++;
            int fromCtx = Math.max(0, i - 2);
            int toCtx = Math.min(allLines.size() - 1, i + 2);
            for (int j = fromCtx; j <= toCtx; j++) {
                if (j <= lastPrinted) continue;
                sb.append(j + 1).append('|').append(allLines.get(j)).append('\n');
                lastPrinted = j;
            }
            sb.append("---\n");
        }
        if (matched == 0) {
            return new ToolExecResult(true,
                    "0 命中（文件共 " + allLines.size() + " 行）；换关键词试，或直接 read_file 分段读",
                    rel + " 内 0 命中");
        }
        if (matched >= maxMatches) sb.append("(已达 " + maxMatches + " 处上限，可能还有更多命中)\n");
        String content = sb.length() > agentProps.getToolResultCapBytes()
                ? sb.substring(0, agentProps.getToolResultCapBytes()) + "\n...(截断)"
                : sb.toString();
        h.readFiles.add(rel);
        h.materials.add("【search_in_file " + rel + " 命中 " + matched + " 处】\n" + content);
        return new ToolExecResult(true, content, rel + " 命中 " + matched + " 处");
    }

    /** 向量语义检索（阶段二）：中文问题直接查英文代码，bge-m3 跨语言 */
    private ToolExecResult toolSemanticSearch(JsonNode args, AgentHarvest h) {
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            return err("INVALID_ARGS", "query 不能为空（用自然语言描述，如「处理商品删除的逻辑」）");
        }
        List<String> keys = new ArrayList<>();
        args.path("project_keys").forEach(k -> {
            String s = k.asText("").trim();
            if (!s.isEmpty() && keys.size() < 3) keys.add(s);
        });
        applyDefaultProjectScope(keys, h);
        try {
            List<dev.example.productai.rag.VectorSearchService.Hit> hits =
                    vectorSearch.search(query, keys, 6);
            if (hits.isEmpty()) {
                return new ToolExecResult(true, "0 命中（当前已建向量索引的项目："
                        + String.join("、", vectorSearch.indexedProjects())
                        + "；目标项目可能未建索引，请换 search_code）",
                        "语义检索 0 命中");
            }
            StringBuilder sb = new StringBuilder("【semantic_search \"" + query + "\"" +
                    (keys.isEmpty() ? "" : " @ " + keys) + "】\n");
            for (dev.example.productai.rag.VectorSearchService.Hit hit : hits) {
                sb.append("- ").append(hit.filePath())
                        .append(" ").append(hit.startLine()).append("-").append(hit.endLine())
                        .append(" 行｜score ").append(String.format("%.2f", hit.score()))
                        .append("\n").append(hit.snippet()).append("\n---\n");
                h.searchedProjects.add(projectKeyOf(hit.filePath()));
            }
            String content = sb.length() > agentProps.getToolResultCapBytes()
                    ? sb.substring(0, agentProps.getToolResultCapBytes()) + "\n...(截断)"
                    : sb.toString();
            h.materials.add(content);
            return new ToolExecResult(true, content, "语义检索命中 " + hits.size() + " 片段");
        } catch (Exception e) {
            return err("SEMANTIC_UNAVAILABLE",
                    "向量检索不可用（" + e.getMessage() + "）；请改用 search_code");
        }
    }

    private String projectKeyOf(String fullPath) {
        int slash = fullPath.indexOf('/');
        return slash > 0 ? fullPath.substring(0, slash) : fullPath;
    }

    private ToolExecResult toolSearchDocs(JsonNode args, AgentHarvest h) {
        List<String> keywords = new ArrayList<>();
        args.path("keywords").forEach(k -> {
            String s = k.asText("").trim();
            if (!s.isEmpty() && keywords.size() < 6) keywords.add(s);
        });
        if (keywords.isEmpty()) {
            return err("INVALID_ARGS", "keywords 不能为空");
        }
        List<MarkdownDocumentIndex.Document> documents = documentIndex.documents();
        if (documents.isEmpty()) {
            ObjectNode n = mapper.createObjectNode();
            n.put("results", "[]");
            n.put("note", "本地无文档目录（docs/ 不存在）");
            return new ToolExecResult(true, n.toString(), "文档目录不存在");
        }
        ArrayNode arr = mapper.createArrayNode();
        try {
            for (MarkdownDocumentIndex.Document document : documents) {
                if (arr.size() >= 3) break;
                Path f = document.path();
                String rel = toRelative(f.toString());
                try {
                    List<String> lines = document.lines();
                    // 章节级检索（v0.10.7）：FAQ 类文档按 ##/### 标题分节，同一关键词会命中
                    // 多个章节——只取第一个命中行的摘录会漏掉后面的章节（真实案例：
                    // "缓存清理"先命中 #来源缓存清理(1105行)，#54 云盘容量缓存清理(1471行)
                    // 从未进材料，模型据材料断言"文档无此条目"）。
                    // 改为：命中所有标题匹配的章节，逐节返回（每文件最多 3 节）。
                    List<int[]> sections = new ArrayList<>();   // [sectionStart, sectionEnd]
                    int picked = 0;
                    for (int hs = 0; hs < lines.size() && picked < 3; hs++) {
                        int level = markdownHeadingLevel(lines.get(hs));
                        if (level == 0) continue;
                        int he = markdownSectionEnd(lines, hs, level);
                        // 父章节已经命中时，不再重复返回它内部的子章节。
                        int candidateStart = hs;
                        boolean covered = sections.stream()
                                .anyMatch(s -> candidateStart >= s[0] && candidateStart <= s[1]);
                        if (covered) continue;
                        String headText = lines.get(hs).toLowerCase();
                        boolean match = keywords.stream().anyMatch(k -> headText.contains(k.toLowerCase()));
                        if (!match) {
                            // 标题不含关键词时，节内正文整节匹配也算（限制节长防大节误吞）
                            if (he - hs > 60) continue;
                            boolean bodyMatch = false;
                            for (int i = hs; i <= he; i++) {
                                String low = lines.get(i).toLowerCase();
                                if (keywords.stream().anyMatch(k -> low.contains(k.toLowerCase()))) {
                                    bodyMatch = true; break;
                                }
                            }
                            if (!bodyMatch) continue;
                        }
                        sections.add(new int[]{hs, he});
                        picked++;
                    }
                    if (sections.isEmpty()) continue;
                    for (int[] sec : sections) {
                        if (arr.size() >= 3) break;
                        StringBuilder sb = new StringBuilder();
                        for (int i = sec[0]; i <= sec[1]; i++) {
                            sb.append(i + 1).append('|').append(lines.get(i)).append('\n');
                        }
                        String excerpt = sb.toString();
                        ObjectNode one = arr.addObject();
                        one.put("doc", f.getFileName().toString());
                        one.put("file", rel);
                        one.put("hitLine", sec[0] + 1);
                        one.put("excerpt", excerpt.length() > 4096
                                ? excerpt.substring(0, 4096) + "\n...(截断)" : excerpt);
                        h.docHits.put(rel, excerpt);
                        h.materials.add("【search_docs 命中 " + rel + " @" + (sec[0] + 1) + " 行】\n"
                                + (excerpt.length() > 3072 ? excerpt.substring(0, 3072) + "\n...(截断)" : excerpt));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            return err("INVALID_ARGS", "文档扫描失败: " + e.getMessage());
        }
        ObjectNode n = mapper.createObjectNode();
        n.set("results", arr);
        if (arr.isEmpty()) {
            n.put("note", "文档无命中，可换关键词重试");
        }
        return new ToolExecResult(true, n.toString(), arr.isEmpty() ? "文档无命中" : "文档命中 " + arr.size() + " 处");
    }

    /** 精确 FAQ 快路径：问题覆盖一个足够具体的 Markdown 标题时，读取该标题的完整层级章节。 */
    private boolean prefetchExactFaqSection(String question, AgentHarvest h) {
        String normalizedQuestion = normalizeDocTitle(question);
        if (normalizedQuestion.isBlank()) return false;
        Path bestFile = null;
        List<String> bestLines = null;
        int bestStart = -1;
        int bestEnd = -1;
        int bestTitleLength = 0;
        try {
            for (MarkdownDocumentIndex.Document document : documentIndex.documents()) {
                Path file = document.path();
                List<String> lines = document.lines();
                for (int i = 0; i < lines.size(); i++) {
                    int level = markdownHeadingLevel(lines.get(i));
                    if (level == 0) continue;
                    String title = lines.get(i).substring(level).trim();
                    String normalizedTitle = normalizeDocTitle(title);
                    // 过短标题（如“处理”“背景”）不具备唯一性，不能触发直答。
                    if (normalizedTitle.length() < 6 || !normalizedQuestion.contains(normalizedTitle)) continue;
                    if (normalizedTitle.length() <= bestTitleLength) continue;
                    bestFile = file;
                    bestLines = lines;
                    bestStart = i;
                    bestEnd = markdownSectionEnd(lines, i, level);
                    bestTitleLength = normalizedTitle.length();
                }
            }
        } catch (Exception e) {
            log.debug("FAQ 精确匹配失败: {}", e.getMessage());
            return false;
        }
        if (bestFile == null || bestLines == null) return false;

        StringBuilder excerpt = new StringBuilder();
        for (int i = bestStart; i <= bestEnd; i++) {
            excerpt.append(i + 1).append('|').append(bestLines.get(i)).append('\n');
        }
        String rel = toRelative(bestFile.toString());
        String text = excerpt.length() > 8192
                ? excerpt.substring(0, 8192) + "\n...(截断)" : excerpt.toString();
        h.docHits.put(rel, text);
        h.materials.add("【search_docs FAQ 标题精确命中 " + rel + " @" + (bestStart + 1) + " 行】\n" + text);
        log.info("FAQ 标题精确命中，跳过代码探索: question={}, file={}, line={}",
                question, rel, bestStart + 1);
        return true;
    }

    /** FAQ 标题精确命中时直接返回原文处理步骤，不再为文档复述额外调用一次大模型。 */
    static String renderExactFaqAnswer(AgentHarvest h) {
        if (h.docHits.isEmpty()) return "FAQ 已命中，但正文为空。";
        String raw = h.docHits.values().iterator().next();
        StringBuilder answer = new StringBuilder("根据本地 FAQ，处理方式如下：\n\n");
        for (String line : raw.split("\\R")) {
            String clean = line.replaceFirst("^\\d+\\|", "");
            clean = clean.replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s)]+", "$1{已脱敏}");
            answer.append(clean).append('\n');
        }
        return answer.toString().trim();
    }

    static boolean isDirectFaqQuestion(String question) {
        if (question == null) return false;
        String q = question.toLowerCase();
        if (q.matches(".*(源码|代码|实现|调用链|原理|为什么|哪[个里]类|哪[个里]方法).*")) return false;
        return q.matches(".*(处理|怎么办|怎么解决|如何操作|怎么操作|清理|失效问题|报错).*" );
    }

    static int markdownHeadingLevel(String line) {
        if (line == null) return 0;
        int level = 0;
        while (level < line.length() && level < 6 && line.charAt(level) == '#') level++;
        return level > 0 && level < line.length() && Character.isWhitespace(line.charAt(level)) ? level : 0;
    }

    static int markdownSectionEnd(List<String> lines, int start, int level) {
        for (int i = start + 1; i < lines.size(); i++) {
            int nextLevel = markdownHeadingLevel(lines.get(i));
            if (nextLevel > 0 && nextLevel <= level) return i - 1;
        }
        return lines.size() - 1;
    }

    static String normalizeDocTitle(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isLetterOrDigit(c) || (c >= '\u4e00' && c <= '\u9fff')) out.append(c);
        }
        return out.toString();
    }

    private static String jsonEscape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private void appendHitLines(SearchResult sr, ArrayNode lines) {
        if (sr.getHitLines() == null || sr.getFilePath() == null) return;
        try {
            List<String> all = searcher.readLines(Paths.get(sr.getFilePath()));
            int n = 0;
            for (Integer ln : sr.getHitLines()) {
                if (n++ >= 8) break;
                int i = ln - 1;
                if (i < 0 || i >= all.size()) continue;
                String s = all.get(i).trim();
                if (s.isEmpty()) continue;
                lines.add(ln + "|" + (s.length() > 200 ? s.substring(0, 200) : s));
            }
        } catch (Exception ignored) {
        }
    }

    private ToolExecResult err(String code, String detail) {
        ObjectNode n = mapper.createObjectNode();
        n.put("error", code);
        n.put("detail", detail);
        return new ToolExecResult(false, n.toString(), code + ": " + detail);
    }

    /** 上下文累计超限：把最旧的 tool 结果替换为占位符 */
    @Override
    public void compressIfNeeded(List<ToolChatMessage> messages) {
        int cap = agentProps.getContextCapBytes();
        long total = 0;
        List<ToolChatMessage> toolMsgs = new ArrayList<>();
        for (ToolChatMessage m : messages) {
            if ("tool".equals(m.getRole()) && m.getContent() != null) {
                total += m.getContent().length();
                toolMsgs.add(m);
            }
        }
        for (ToolChatMessage m : toolMsgs) {
            if (total <= cap) break;
            total -= m.getContent().length();
            m.setContent("[已压缩：早期工具结果被丢弃以控制上下文长度]");
        }
    }

    // ==================================================================================
    // 可用性 / 提示词 / 工具 schema
    // ==================================================================================

    /** projects.yml 声明的项目在本地是否实际存在（目录存在且非空）；克隆后自动恢复 */
    @Override
    public Map<String, Boolean> computeAvailability() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
        for (ProjectInfo p : loader.getProjects()) {
            String dir = p.getPath() == null || p.getPath().isBlank() ? p.getName() : p.getPath();
            Path d = base.resolve(dir).normalize();
            boolean ok = false;
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.list(d)) {
                    ok = s.findAny().isPresent();
                } catch (Exception ignored) {
                }
            }
            map.put(p.getName(), ok);
        }
        long missing = map.values().stream().filter(v -> !v).count();
        if (missing > 0) log.info("项目可用性：{} 个可用，{} 个未下载", map.size() - missing, missing);
        return map;
    }

    @Override
    public String buildExploreSystemPrompt(String mode, Map<String, Boolean> availability,
                                           int budget, boolean deepTrace, int graceTurns) {
        StringBuilder cat = new StringBuilder();
        for (ProjectInfo p : loader.getProjects()) {
            boolean ok = availability.getOrDefault(p.getName(), false);
            cat.append("- ").append(p.getName());
            if (p.getDescription() != null) cat.append("｜").append(p.getDescription());
            if (p.getTags() != null && !p.getTags().isEmpty()) {
                List<String> tags = p.getTags().subList(0, Math.min(3, p.getTags().size()));
                cat.append("｜tags:").append(String.join(",", tags));
            }
            if (!ok) cat.append("｜【未下载】");
            cat.append('\n');
        }
        String modeNote = switch (mode) {
            case "dev" -> "dev（开发者排查：格式服从用户本轮明确要求；未要求画图时不生成流程图）";
            case "analyze" -> "analyze（接口分析）";
            default -> "user（业务用户：成文口语化、直接结论、内联 [文件:行号] 引用，禁 ## 标题）";
        };
        return """
                你是本地项目的代码问答智能体，在本地代码库与文档里排查问题、收集回答材料。
                只做工具决策，不要在决策轮写面向用户的回答；材料足够时停止调用工具，
                且无工具调用的 content 只能返回短标记 [READY]，禁止在决策轮生成长答案。

                ## 可用项目目录（key｜说明｜状态）
                %s
                ## 工具使用规范
                1) search_code：检索源码。project_keys 选填（1~6 个，从上面目录选）；
                   用户写明项目时，不填 project_keys 只搜该项目；确有已读源码证明的跨服务调用时，
                   显式写出提供方 project_keys。用户没有指定项目且无法从问题判断时，才全库搜。
                   中文业务词要转成代码里的英文词再搜，拿不准就并列多种变体
                   （英文直译和拼音驼峰一起给，如 上下架=UpDown、回收站=RecycleBin、
                   标签=Tag/Label、收藏=Favor/Collect），一次关键词多给几个命中率更高；
                   URL 路径可以整段作为关键词（精确命中）。
                   选择启发式——精确符号/URL/类名 → search_code；语义模糊的业务描述、
                   不知道类名方法名、或关键词搜不到时 → semantic_search（中文直接查，
                   注意它只覆盖已建向量索引的项目）；不确定就先 search_code，空了再 semantic_search 换个思路。
                2) read_file：看具体实现。file_path 用 search_code 结果里的 file 字段原样传入。
                   千行级大文件先用 search_in_file 定位行号再读目标区间，不要反复翻页；
                   在同一个文件里找方法签名/调用点时，search_in_file 一次调用即可。
                3) get_project_info：查某个项目的元数据与 RPC 上下游依赖链。
                4) search_docs：检索本地文档（docs/faq 下的现网FAQ等）。
                   「XX怎么处理/怎么操作/报错怎么办」这类线上处理流程类问题，优先用它。
                5) 标注【未下载】的项目不要检索；如果它是问题的主目标，最后回答时提示用户先克隆。
                 6) 跨服务链路排查（BFF → 业务服务 → core/提供方）：在调用方看到 Feign/RPC 接口引用后，
                   应尽快跳到提供方项目检索实现类（XxxFeignClient 对端通常是 XxxRpc/XxxController），
                   不要在调用方项目层层深挖耗尽预算。
                7) 一轮可以并行发多个工具调用；结果为空就换关键词/换项目再试，不要重复同一调用。
                   如果问题只有“批量删除接口”这类泛操作词，且没有删除对象、项目或明确的上文指代，
                   可以根据上下文与检索结果给出最可能的接口，但要明确说明是按哪个业务对象回答；
                   其他对象可能有不同入口。不要只追问而不给已找到的结果。
                8) 回答模式：%s。

                 ## 调用链完成标准
                 - 接口、RPC/Feign、Facade、抽象 Service 都不是调用链终点，不能因为找到它们就停止。
                 - 只有追到数据库 Repository/Mapper/表、ES 索引、Redis、MQ、文件存储或明确外部系统，
                   或者已经用搜索证明提供方源码确实不可用，才可以认为该分支闭环。
                 - 用户要求深挖时，要优先闭合主链路；材料不足应继续调用工具，不能提前写猜测性结论。

                ## 预算
                 你最多有 %d 轮计划决策机会，当前问题按需使用，够用即停。%s
                """.formatted(cat, modeNote, budget,
                deepTrace ? "这是深度追踪请求；最后一轮仍发现新线索时，系统最多追加 " + graceTurns + " 轮收尾。" : "");
    }

    private String buildTaskSpecificExploreRules(AskContext c) {
        if (!c.isTargetedConfigLookup()) return "";
        return """

                ## 本题专用完成标准：精确配置定位
                - 用户要的是指定接口实际受哪个配置/开关控制，不是通用的数据落库调用链。
                - 必须确认三类证据：配置/开关的准确名称或字段、代码读取点、不同取值对应的分支作用。
                - 找到上述证据后即视为闭环，返回 [READY]；不要为了追到 DB/Redis/MQ 继续展开无关链路。
                - 配置框架的基类/第三方依赖（如 ConfigurationSwitcher）不是本题证据终点；已有配置注解、
                  业务读取点和分支代码时不要再追框架内部实现。
                - 系统已经做过接口入口预检；优先消费预检结果，不要重复搜索相同 URL 或重复读取同一文件。
                - 用户指定了项目。只有已读源码出现明确 RPC/Feign 提供方且该提供方影响配置结论时，
                  才能用显式 project_keys 扩展到那个项目；不得省略 project_keys 扫描全部项目。
                - 同一轮能确定的多个符号/文件必须批量发出工具调用，减少串行决策轮，但不能跳过证据核验。
                """;
    }

    String buildInitialUserPrompt(String question, ConversationSession session, AskContext c) {
        String history = renderHistory(session);
        StringBuilder goal = new StringBuilder();
        if (c.getApiPath() != null && !c.getApiPath().isBlank()) {
            goal.append("\n目标接口：").append(c.getApiPath());
        }
        if (c.getIntent() != null && !c.getIntent().isBlank()) {
            goal.append("\n分析目标：").append(c.getIntent());
        }
        if (c.getSpecifiedProjects() != null && !c.getSpecifiedProjects().isEmpty()) {
            goal.append("\n用户指定的重点项目：").append(String.join(", ", c.getSpecifiedProjects()))
                    .append("（优先在其中检索；追跨服务调用链时可以查其他项目）");
        }
        return "用户问题：" + question + goal
                + "\n\n历史对话（最近若干轮，可能为空）：\n" + history
                + "\n\n请决定第一步调用什么工具。";
    }

    @Override
    public String buildFinalSystemPrompt(String mode, List<String> exploredProjects,
                                         boolean flowchart, boolean concise) {
        String proj = exploredProjects.isEmpty() ? "(未确定)" : String.join(", ", exploredProjects);
        if (concise) {
            return """
                    你是本地项目的代码问答助手。当前使用“业务简报”回答档位：
                    内部可以完成充分的代码和文档调查，但用户只需要可直接使用的最终结果。

                    要求：
                    1) 严格基于材料，只回答用户点名要的内容；第一句话直接给结论。
                    2) 不生成 Mermaid/流程图，不写逐跳分析模板，不附汇总表，不扩展架构背景。
                    3) 如果用户问“改什么配置”，直接给配置中心、配置名、字段和值；必要条件用短列表补充。
                    4) 默认不展开调用链、类名、方法名、数据库表和搜索过程；只有结论存在歧义时，
                       才点出一个关键技术落点。源码依据由界面的“查看依据”承载，不必在正文堆砌。
                    5) 最多补充 1～3 条真正影响使用的条件、操作或例外；没有必要就只写一段。
                    6) 通常控制在 80～250 个中文字符；材料不足时用一句话说明缺口。
                    7) 配置标识必须逐字段核对，尤其不能把 dataId 和 group 写反；
                       “高确定性结构化事实”优先级高于模型对位置参数的猜测。

                    回答最末尾追加一行：[CONFIDENCE:high] 或 [CONFIDENCE:medium] 或 [CONFIDENCE:low]

                    本次探索涉及项目：""" + proj;
        }
        if ("dev".equals(mode) || "analyze".equals(mode)) {
            String flavor = "analyze".equals(mode)
                    ? "当前处于\"接口分析模式\"：围绕用户指定的接口，把 Controller → Service 实现 → 下游调用 的完整链路讲清。" +
                      "详略必须跟随用户「分析目标」的深浅自动调节：" +
                      "目标偏整体/业务理解（如 整体逻辑 / 是做什么的 / 流程 / 给测试或前端讲解）→ " +
                      "以业务视角为主：接口职责、输入输出、主流程与关键分支、权限/缓存等行为级特征，" +
                      "每一跳一两句话点到即可，数据层实现细节（分库分表、表名、SQL、线程池参数）不展开；" +
                      "目标偏实现/排查细节（如 怎么实现的 / 为什么慢 / 哪里出问题 / 具体逻辑）→ 才逐跳深挖方法、分支与数据源。" +
                      "拿不准时按偏整体处理——先给业务总览，再补一层技术要点。"
                    : "当前处于\"开发者排查模式\"。";
            String diagramRule = flowchart
                    ? """
                    2) 回答开头先输出 mermaid 流程图代码块（```mermaid 包裹，flowchart TD，节点 label ≤30 字符且不含裸括号），
                       把调用链画出来；没有链路就画单节点或省略图。"""
                    : """
                    2) 本次回答不生成 mermaid 流程图，直接进入正文讲解（调用链用文字逐跳描述）。""";
            return """
                    你是本地项目的代码问答助手，%s 你会拿到一份「探索收集的材料」，基于它作答。

                    要求：
                    1) 严格基于材料回答，不要编造材料里不存在的类、方法、行号；材料不足的环节明确说"该环节未检索到"。
                    %s
                    3) 按「第 N 跳：服务｜`类#方法`（`文件:行号`）」组织链路，详略按上述目标深浅规则执行。
                    4) 末尾附汇总表：| 层级 | 服务 | 关键类#方法 | 数据源 | 代码位置 |（整体类目标可精简为 3~5 行）。
                    5) 类名方法名用反引号；不用 ## 大标题。

                    回答最末尾追加一行：[CONFIDENCE:high] 或 [CONFIDENCE:medium] 或 [CONFIDENCE:low]

                    本次探索涉及项目：""".formatted(flavor, diagramRule) + proj;
        }
        return """
                你是本地项目的代码问答助手，像组里熟代码的同事回答提问一样自然、直接、口语化。
                你会拿到一份「探索收集的材料」（代码命中、文件内容、文档摘录）。

                要求：
                1) 严格基于材料回答，不编造类、方法、字段；材料不足直接说明，不要硬答。
                2) 第一句话先给直接结论（在哪、谁来处理、怎么处理）。
                3) 关键落点给引用，格式内联 [文件:行号] 或 [文档名:行号]；线上处理流程类问题按文档步骤讲。
                4) 【文档优先，重要】"怎么处理 / 怎么操作 / 怎么清理 / 报错怎么办"这类线上处理问题，
                   若材料里有文档命中（search_docs 摘录），**必须以文档口径为主答案**：文档给的
                   接口 / URL / 步骤原样端出来（URL 用代码块完整贴出），代码分析只作背景补充；
                   文档口径与代码结论不一致时以文档为准并说明差异。内部 Feign/Service 接口
                   不能替代文档给的运维 URL。
                5) 自然段为主，禁 ## 标题；类名方法名用反引号；300-600 字，复杂可到 900。

                回答最末尾追加一行：[CONFIDENCE:high] 或 [CONFIDENCE:medium] 或 [CONFIDENCE:low]

                本次探索涉及项目：""" + proj;
    }

    private String buildFinalUserPrompt(String question, ConversationSession session, AgentHarvest h, String mode,
                                        boolean incomplete, String incompleteReason) {
        StringBuilder digest = new StringBuilder();
        long budget = agentProps.getContextCapBytes();
        for (String m : distillMaterials(h)) {
            if (digest.length() + m.length() > budget) {
                digest.append("\n...(材料过长已截断)\n");
                break;
            }
            digest.append(m).append('\n');
        }
        String verifiedFacts = verifiedConfigFacts(h);
        return """
                用户问题：%s

                历史对话（可能为空）：
                %s

                探索收集的材料（唯一可信来源）：
                %s

                高确定性结构化事实（由本地代码规则提取，存在时必须逐字遵守）：
                %s

                探索状态：%s%s

                请按 %s 模式要求回答。若探索状态为 PARTIAL，必须明确区分“已验证链路”和“尚未验证的下一跳”，
                不得声称已经查到底；末尾追加 [CONFIDENCE:xxx]。
                """.formatted(question, renderHistory(session), digest,
                verifiedFacts.isBlank() ? "（无）" : verifiedFacts,
                incomplete ? "PARTIAL" : "COMPLETE",
                incompleteReason == null ? "" : "（" + incompleteReason + "）", mode);
    }

    /**
     * 将容易被模型写反的配置位置参数转成有标签的事实。
     * SwitcherID 的公共契约已经在业务源码中验证为 (dataID, groupID, type)。
     */
    static String verifiedConfigFacts(AgentHarvest h) {
        if (h == null || h.materials.isEmpty()) return "";
        LinkedHashSet<String> facts = new LinkedHashSet<>();
        for (String material : h.materials) {
            Matcher matcher = SWITCHER_ID_LITERAL.matcher(material);
            while (matcher.find() && facts.size() < 8) {
                facts.add("SwitcherID：dataId=\"" + matcher.group(1)
                        + "\"，group=\"" + matcher.group(2) + "\"");
            }
        }
        return String.join("\n", facts);
    }

    /**
     * 蒸馏式材料压缩（v0.9）：替代"按序塞满硬截断"。
     * 原则：①完整重复块去重；②0 命中/空结果剔除；③按信息密度排序
     * （read_file / search_in_file 完整保留 > search_code 聚合块限量 > docs 摘要限量），
     * 让预算优先花在模型深挖出的关键文件上，而不是探索早期的泛命中样例。
     */
    @Override
    public List<String> distillMaterials(AgentHarvest h) {
        List<String> dense = new ArrayList<>();    // read_file / search_in_file：完整保留
        List<String> samples = new ArrayList<>();  // search_code 聚合块：限量
        List<String> docs = new ArrayList<>();     // search_docs 摘录：限量
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String m : h.materials) {
            if (m == null || m.isBlank()) continue;
            String key = m.trim();
            if (!seen.add(key)) continue;                       // 完全重复的块只留一份
            String compact = key.replaceAll("\\s+", " ");
            if (compact.contains("命中 0 文件") || compact.contains("0 命中")
                    || compact.contains("文档目录不存在")) continue;   // 无效材料剔除
            if (m.startsWith("【read_file") || m.startsWith("【search_in_file")) {
                dense.add(m);
            } else if (m.startsWith("【search_code")) {
                samples.add(capBlock(m, 4096));                 // 聚合样例块限量 4KB
            } else {
                docs.add(capBlock(m, 3072));
            }
        }
        List<String> ordered = new ArrayList<>(dense.size() + samples.size() + docs.size());
        // 文档块提到最前：search_docs 是模型的主动决策，文档命中即高相关；
        // 排最后会被"更长的代码叙事"淹没——真实案例：FAQ#54 的运维 URL 在材料里
        // 却没进答案，模型全文以代码口径成文（2026-08-31 云盘容量缓存清理）。
        ordered.addAll(docs);
        ordered.addAll(dense);
        ordered.addAll(samples);
        log.info("材料蒸馏：{} 块 → {} 块（文档 {} / 实读 {} / 检索样例 {}）",
                h.materials.size(), ordered.size(), docs.size(), dense.size(), samples.size());
        return ordered;
    }

    /** 单块材料超长时截断（保留头部标记，头部含文件路径与行区间信息） */
    private String capBlock(String m, int maxChars) {
        return m.length() <= maxChars ? m : m.substring(0, maxChars) + "\n...(本块材料已限量截断)";
    }

    @Override
    public String renderHistory(ConversationSession session) {
        List<ChatMessage> hist = session.snapshot();
        if (hist.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : hist) {
            sb.append("  ").append("user".equals(m.getRole()) ? "用户" : "AI").append("：")
                    .append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    /** semantic_search 工具描述里附上实际已建索引的项目，避免模型对覆盖范围误判 */
    private String vectorCoverageNote() {
        List<String> ps = vectorSearch.indexedProjects();
        return ps.isEmpty() ? "" : "当前已建索引项目（仅这些项目可搜到）：" + String.join("、", ps) + "。";
    }

    @Override
    public List<Map<String, Object>> toolSchemas() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(Map.of("type", "function", "function", Map.of(
                "name", "search_code",
                "description", "在项目源码内做关键词检索（grep 风格，URL路径可精确命中）。project_keys 不填 = 全部本地项目全库搜（适合不知道所属项目的 URL/类名）。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keywords", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "检索关键词，OR 关系，1~8 个"),
                                "project_keys", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "检索的项目 key 列表，1~6 个；URL 路径/类名等全局唯一符号且不知道所属项目时可省略 = 全库搜"),
                                "file_glob", Map.of("type", "string", "description", "可选，文件名过滤，如 *Controller.java")),
                        "required", List.of("keywords")))));
        tools.add(Map.of("type", "function", "function", Map.of(
                "name", "read_file",
                "description", "读取源码文件的指定行区间（默认 1-300 行，带行号）。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "相对 code-base 的正斜杠路径，用 search_code 结果的 file 原样传入"),
                                "start_line", Map.of("type", "integer"),
                                "end_line", Map.of("type", "integer")),
                        "required", List.of("file_path")))));
        tools.add(Map.of("type", "function", "function", Map.of(
                "name", "search_in_file",
                "description", "在单个文件内 grep：返回所有命中行及行号（±2 行上下文）。千行级大文件里定位方法签名/字段/调用点用这个，一次顶多次 read_file 翻页。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of("type", "string", "description", "相对 code-base 的正斜杠路径，用 search_code 结果的 file 原样传入"),
                                "keywords", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "关键词，OR 关系，不区分大小写，1~6 个")),
                        "required", List.of("file_path", "keywords")))));
        tools.add(Map.of("type", "function", "function", Map.of(
                "name", "get_project_info",
                "description", "查项目元数据：描述、features、RPC 上下游依赖链（rpc_to/called_by）、本地是否已下载。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "project_key", Map.of("type", "string", "description", "项目 key")),
                        "required", List.of("project_key")))));
        tools.add(Map.of("type", "function", "function", Map.of(
                "name", "search_docs",
                "description", "检索本地文档（现网店铺商品常见问题处理FAQ等）。线上问题处理流程、运营规则、报错处理步骤类问题用它。",
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "keywords", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "文档关键词，1~6 个")),
                        "required", List.of("keywords")))));
        tools.add(Map.of("type", "function", "function", Map.of(
                "name", "semantic_search",
                "description", "向量语义检索代码（本地 embedding，中文可直接查英文代码）。适合：语义模糊的业务描述、关键词搜不到、不知道类名/方法名时。返回最相关代码片段+行号+相似度。" + vectorCoverageNote(),
                "parameters", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "自然语言描述，如「处理商品删除后回收站的逻辑」「小程序码生成的实现」"),
                                "project_keys", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "可选，限定项目 1~3 个；缺省搜全部已索引项目")),
                        "required", List.of("query")))));
        return tools;
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    private void putStrArr(ArrayNode arr, List<String> src, int cap) {
        if (src == null) return;
        src.stream().limit(cap).forEach(arr::add);
    }

    private String prettyJson(String raw) {
        try {
            Object o = mapper.readValue(raw == null ? "{}" : raw, Object.class);
            String s = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
            return s.length() > 400 ? s.substring(0, 400) + "..." : s;
        } catch (Exception e) {
            return raw;
        }
    }

    /** 绝对路径转相对 code-base 的短路径（正斜杠） */
    @Override
    public String toRelative(String filePath) {
        try {
            Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
            Path p = Paths.get(filePath).toAbsolutePath().normalize();
            return base.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return filePath.replace('\\', '/');
        }
    }

    /** 切分正文和末尾置信度标记 */
    @Override
    public String[] parseConfidence(String resp) {
        if (resp == null) return new String[]{"", "low"};
        int idx = resp.lastIndexOf("[CONFIDENCE:");
        if (idx < 0) return new String[]{resp.trim(), "medium"};
        String body = resp.substring(0, idx).trim();
        String tail = resp.substring(idx).toLowerCase();
        String confidence = "medium";
        if (tail.contains("high")) confidence = "high";
        else if (tail.contains("low")) confidence = "low";
        return new String[]{body, confidence};
    }
}
