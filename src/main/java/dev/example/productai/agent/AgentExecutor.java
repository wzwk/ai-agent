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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class AgentExecutor {

    private final LlmClient llm;
    private final CodeSearcher searcher;
    private final ProjectsConfigLoader loader;
    private final ProjectsProperties projectsProps;
    private final SearchProperties searchProps;
    private final AgentProperties agentProps;
    private final dev.example.productai.rag.VectorSearchService vectorSearch;
    private final MarkdownDocumentIndex documentIndex;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final class RuntimeMetrics {
        long decisionMs;
        long toolMs;
        long composeMs;
        int decisionCalls;
        int toolCalls;
    }

    /** 一次工具执行的返回：content 回喂模型，summary 给时间线 */
    record ExecResult(boolean ok, String content, String summary) {
    }

    /**
     * SSE 实时流钩子（可空）：
     * onStep —— 每个工具调用/结果/强制成文事件实时推送；
     * onDelta —— 成文轮的每个文本增量（打字机效果）。
     */
    public record StreamHooks(java.util.function.Consumer<ChatResponse.Step> onStep,
                              java.util.function.Consumer<String> onDelta) {
        public static StreamHooks none() { return null; }
    }

    /** 探索过程的收集物 */
    static class Harvest {
        final Set<String> searchedProjects = new LinkedHashSet<>();
        final Map<String, SearchResult> codeHits = new LinkedHashMap<>();   // relPath -> hit
        final Map<String, String> docHits = new LinkedHashMap<>();          // relPath -> excerpt
        final Set<String> readFiles = new LinkedHashSet<>();
        final List<String> materials = new ArrayList<>();                    // 成文用的材料块
        List<String> specifiedProjects = List.of();                          // 用户指定项目（首轮检索限定范围）
    }

    // ==================================================================================
    // 入口（由 ProductAgent 在配额检查后调用）
    // ==================================================================================

    public ChatResponse execute(AskContext c, ConversationSession session,
                                String mode, Integer freeRemaining, long start) {
        return execute(c, session, mode, freeRemaining, start, null);
    }

    public ChatResponse execute(AskContext c, ConversationSession session,
                                String mode, Integer freeRemaining, long start,
                                StreamHooks hooks) {
        String question = c.getQuestion();
        boolean deepTrace = agentProps.isDeepTraceRequest(question, c.getIntent());
        int plannedBudget = agentProps.budgetFor(mode, deepTrace);
        int activeBudget = plannedBudget;
        int graceTurns = agentProps.graceTurnsFor(deepTrace);
        boolean graceGranted = false;
        Harvest h = new Harvest();
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
                mode, availability, plannedBudget, deepTrace, graceTurns)));
        messages.add(ToolChatMessage.user(buildInitialUserPrompt(question, session, c)));

        List<Map<String, Object>> tools = toolSchemas();
        Map<String, Integer> failStreak = new HashMap<>();
        int turn = exactFaqHit ? 1 : 0;
        int usedTokens = 0;
        boolean modelDone = exactFaqHit;
        boolean tokenGateHit = false;
        String inlineAnswer = exactFaqHit ? renderExactFaqAnswer(h) + "\n\n[CONFIDENCE:high]" : null;

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

                ExecResult er = executeTool(call, availability, h, failStreak);
                successfulToolThisTurn |= er.ok();
                long cost = System.currentTimeMillis() - t0;
                metrics.toolCalls++;
                metrics.toolMs += cost;
                addStep(steps, emit, ChatResponse.Step.builder().no(turn).phase("tool_result")
                        .tool(call.name()).summary(er.summary()).elapsedMs(cost)
                        .status(er.ok() ? "ok" : "error").build());
                messages.add(ToolChatMessage.tool(call.id(), er.content()));
            }
            compressIfNeeded(messages);
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
                        c.useFlowchart());
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

    /** 步骤入列 + 实时发射（SSE 钩子存在时） */
    private void addStep(List<ChatResponse.Step> steps,
                         java.util.function.Consumer<ChatResponse.Step> emit,
                         ChatResponse.Step st) {
        steps.add(st);
        if (emit != null) emit.accept(st);
    }

    void checkCancelled() {
        java.util.concurrent.atomic.AtomicBoolean t = LlmCancellation.token();
        if (t != null && t.get()) {
            throw new LlmCancelledException();
        }
    }

    // ==================================================================================
    // 工具执行
    // ==================================================================================

    ExecResult executeTool(ToolCall call, Map<String, Boolean> availability,
                           Harvest h, Map<String, Integer> failStreak) {
        String key = call.name() + "|" + call.arguments();
        try {
            JsonNode args = mapper.readTree(call.arguments() == null ? "{}" : call.arguments());
            ExecResult r = switch (call.name() == null ? "" : call.name()) {
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

    private ExecResult toolGetProjectInfo(JsonNode args, Map<String, Boolean> availability) {
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
        return new ExecResult(true, n.toString(),
                p.getName() + " 元数据（" + (available ? "可用" : "未下载") + "）");
    }

    private ExecResult toolSearchCode(JsonNode args, Map<String, Boolean> availability, Harvest h) {
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
        // P0：用户已指定项目时，首轮 search_code 不得全库搜（全 42 项目 grep 一次约 18s）。
        // 仅限首轮（searchedProjects 为空）；后续追跨服务 Feign/RPC 链路时仍可显式带 project_keys 扩范围。
        if (keys.isEmpty() && h.searchedProjects.isEmpty() && !h.specifiedProjects.isEmpty()) {
            keys.addAll(h.specifiedProjects);
        }
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
        return new ExecResult(true, n.toString(), summary);
    }

    private ExecResult toolReadFile(JsonNode args, Harvest h) {
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
        return new ExecResult(true, content, "读取 " + rel + " " + from + "-" + to + " 行");
    }

    /** 单文件内 grep：返回所有命中行 + 行号（±2 行上下文），专治大文件里定位方法/调用点 */
    private ExecResult toolSearchInFile(JsonNode args, Harvest h) {
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
            return new ExecResult(true,
                    "0 命中（文件共 " + allLines.size() + " 行）；换关键词试，或直接 read_file 分段读",
                    rel + " 内 0 命中");
        }
        if (matched >= maxMatches) sb.append("(已达 " + maxMatches + " 处上限，可能还有更多命中)\n");
        String content = sb.length() > agentProps.getToolResultCapBytes()
                ? sb.substring(0, agentProps.getToolResultCapBytes()) + "\n...(截断)"
                : sb.toString();
        h.readFiles.add(rel);
        h.materials.add("【search_in_file " + rel + " 命中 " + matched + " 处】\n" + content);
        return new ExecResult(true, content, rel + " 命中 " + matched + " 处");
    }

    /** 向量语义检索（阶段二）：中文问题直接查英文代码，bge-m3 跨语言 */
    private ExecResult toolSemanticSearch(JsonNode args, Harvest h) {
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            return err("INVALID_ARGS", "query 不能为空（用自然语言描述，如「处理商品删除的逻辑」）");
        }
        List<String> keys = new ArrayList<>();
        args.path("project_keys").forEach(k -> {
            String s = k.asText("").trim();
            if (!s.isEmpty() && keys.size() < 3) keys.add(s);
        });
        try {
            List<dev.example.productai.rag.VectorSearchService.Hit> hits =
                    vectorSearch.search(query, keys, 6);
            if (hits.isEmpty()) {
                return new ExecResult(true, "0 命中（当前已建向量索引的项目："
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
            return new ExecResult(true, content, "语义检索命中 " + hits.size() + " 片段");
        } catch (Exception e) {
            return err("SEMANTIC_UNAVAILABLE",
                    "向量检索不可用（" + e.getMessage() + "）；请改用 search_code");
        }
    }

    private String projectKeyOf(String fullPath) {
        int slash = fullPath.indexOf('/');
        return slash > 0 ? fullPath.substring(0, slash) : fullPath;
    }

    private ExecResult toolSearchDocs(JsonNode args, Harvest h) {
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
            return new ExecResult(true, n.toString(), "文档目录不存在");
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
        return new ExecResult(true, n.toString(), arr.isEmpty() ? "文档无命中" : "文档命中 " + arr.size() + " 处");
    }

    /** 精确 FAQ 快路径：问题覆盖一个足够具体的 Markdown 标题时，读取该标题的完整层级章节。 */
    private boolean prefetchExactFaqSection(String question, Harvest h) {
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
    static String renderExactFaqAnswer(Harvest h) {
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

    private ExecResult err(String code, String detail) {
        ObjectNode n = mapper.createObjectNode();
        n.put("error", code);
        n.put("detail", detail);
        return new ExecResult(false, n.toString(), code + ": " + detail);
    }

    /** 上下文累计超限：把最旧的 tool 结果替换为占位符 */
    void compressIfNeeded(List<ToolChatMessage> messages) {
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
    Map<String, Boolean> computeAvailability() {
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

    String buildExploreSystemPrompt(String mode, Map<String, Boolean> availability,
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
            case "dev" -> "dev（开发者排查：成文需 mermaid 流程图 + 逐跳讲解 + 汇总表，引用 文件:行号）";
            case "analyze" -> "analyze（接口分析）";
            default -> "user（业务用户：成文口语化、直接结论、内联 [文件:行号] 引用，禁 ## 标题）";
        };
        return """
                你是商品研发团队的内部智能体，在本地代码库与文档里排查问题、收集回答材料。
                只做工具决策，不要在决策轮写面向用户的回答；材料足够时停止调用工具。

                ## 可用项目目录（key｜说明｜状态）
                %s
                ## 工具使用规范
                1) search_code：检索源码。project_keys 选填（1~6 个，从上面目录选）；
                   不填 = 全部本地项目全库搜——URL 路径/注解/类名这类全局唯一符号、
                   且从问题里猜不出所属项目时，直接全库搜，不要猜项目。
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

    String buildFinalSystemPrompt(String mode, List<String> exploredProjects, boolean flowchart) {
        String proj = exploredProjects.isEmpty() ? "(未确定)" : String.join(", ", exploredProjects);
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
                    你是商品研发团队的内部 AI 助手，%s 你会拿到一份「探索收集的材料」，基于它作答。

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
                你是商品研发团队的内部 AI 助手，像组里熟代码的同事回答提问一样自然、直接、口语化。
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

    private String buildFinalUserPrompt(String question, ConversationSession session, Harvest h, String mode,
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
        return """
                用户问题：%s

                历史对话（可能为空）：
                %s

                探索收集的材料（唯一可信来源）：
                %s

                探索状态：%s%s

                请按 %s 模式要求回答。若探索状态为 PARTIAL，必须明确区分“已验证链路”和“尚未验证的下一跳”，
                不得声称已经查到底；末尾追加 [CONFIDENCE:xxx]。
                """.formatted(question, renderHistory(session), digest,
                incomplete ? "PARTIAL" : "COMPLETE",
                incompleteReason == null ? "" : "（" + incompleteReason + "）", mode);
    }

    /**
     * 蒸馏式材料压缩（v0.9）：替代"按序塞满硬截断"。
     * 原则：①完整重复块去重；②0 命中/空结果剔除；③按信息密度排序
     * （read_file / search_in_file 完整保留 > search_code 聚合块限量 > docs 摘要限量），
     * 让预算优先花在模型深挖出的关键文件上，而不是探索早期的泛命中样例。
     */
    List<String> distillMaterials(Harvest h) {
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

    String renderHistory(ConversationSession session) {
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

    List<Map<String, Object>> toolSchemas() {
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
    String toRelative(String filePath) {
        try {
            Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
            Path p = Paths.get(filePath).toAbsolutePath().normalize();
            return base.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return filePath.replace('\\', '/');
        }
    }

    /** 切分正文和末尾置信度标记 */
    String[] parseConfidence(String resp) {
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
