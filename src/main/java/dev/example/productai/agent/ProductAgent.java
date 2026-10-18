package dev.example.productai.agent;

import dev.example.productai.config.AgentProperties;
import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.conversation.ConversationStore;
import dev.example.productai.repository.SemanticCacheService;
import dev.example.productai.llm.LlmClient;
import dev.example.productai.llm.LlmRuntime;
import dev.example.productai.model.ChatMessage;
import dev.example.productai.model.ChatResponse;
import dev.example.productai.model.ConversationSession;
import dev.example.productai.model.ProjectInfo;
import dev.example.productai.router.ProjectRouter;
import dev.example.productai.router.RouteResult;
import dev.example.productai.search.CodeSearcher;
import dev.example.productai.search.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主编排 Agent：
 * 1) 调用 ProjectRouter 做项目路由（按当前问题，不掺杂历史）
 * 2) 调用 CodeSearcher 在锁定项目内做关键词检索
 * 3) 拼接代码上下文 + 会话历史，调用 LLM 生成回答
 * 4) 解析置信度，决定是否需要人工
 *
 * 支持双模式：
 *   - user：面向业务用户的口语化回答
 *   - dev：面向研发的排查向回答，开头强制输出 mermaid 流程图
 *
 * 支持多轮对话：通过 sessionId 关联历史，最终答题 LLM 可看到最近 N 轮 Q&A。
 
 * @author wengzewei*/
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductAgent {

    private final ProjectRouter router;
    private final CodeSearcher searcher;
    private final LlmClient llm;
    private final ProjectsConfigLoader loader;
    private final ProjectsProperties projectsProps;
    private final SearchProperties searchProps;
    private final ConversationStore conversationStore;
    private final AgentProperties agentProps;
    private final AgentExecutor agentExecutor;
    private final TraceGraphExecutor traceGraphExecutor;
    private final dev.example.productai.repository.PersistenceService persistence;
    private final SemanticCacheService semanticCache;

    /** 每个会话可用共享 Key 免费提问的次数；访客自带 Key 时不占额度 */
    @Value("${app.share.free-questions:1}")
    private int freeQuestions;

    public ChatResponse ask(String question) {
        return ask(question, "user", null, null);
    }

    public ChatResponse ask(String question, String mode, String sessionId, LlmRuntime rt) {
        return ask(AskContext.builder()
                .question(question).mode(mode).sessionId(sessionId).rt(rt)
                .build());
    }

    public ChatResponse ask(AskContext c) {
        return askStream(c, null);
    }

    /** SSE 版入口：hooks 非空时步骤/成文增量实时回调（引擎须为 agent 才有流式效果） */
    public ChatResponse askStream(AskContext c, AgentExecutor.StreamHooks hooks) {
        long start = System.currentTimeMillis();
        String question = c.getQuestion();
        String effectiveMode = (c.getMode() == null || c.getMode().isBlank()) ? "user" : c.getMode().trim().toLowerCase();
        LlmRuntime rt = c.getRt();
        boolean byok = rt != null && rt.hasKey();
        log.info("=== 用户提问: {}, mode={}, sessionId={}, byok={}, 指定项目={} ===",
                question, effectiveMode, c.getSessionId(), byok,
                c.getSpecifiedProjects() == null ? "自动路由" : c.getSpecifiedProjects());

        ConversationSession session = conversationStore.getOrCreate(c.getSessionId());
        boolean deepTrace = agentProps.isDeepTraceRequest(question, c.getIntent());
        boolean traceEngine = useAgentEngine(c) && useTraceEngine(c);
        boolean directFaqRequest = "user".equals(effectiveMode) && AgentExecutor.isDirectFaqQuestion(question);

        boolean defaultEngine = c.getEngine() == null || c.getEngine().isBlank();
        // trace 只允许命中“同题 + COMPLETE + trace 引擎 + 引用源码未变化”的严格缓存；
        // legacy/workflow 继续使用原有高阈值语义缓存。显式指定 engine=trace 表示强制重查。
        if (defaultEngine && traceEngine) {
            SemanticCacheService.CacheHit hit = semanticCache.findVerifiedExact(question, effectiveMode);
            if (hit != null) {
                ChatResponse resp = cachedResponse(hit, session, effectiveMode, start);
                session.append(new ChatMessage("user", question));
                session.append(new ChatMessage("assistant", hit.answer()));
                persistence.saveTurn(session, effectiveMode, question, resp);
                return resp;
            }
        } else if (defaultEngine && !deepTrace && !directFaqRequest) {
            SemanticCacheService.CacheHit hit = semanticCache.find(question, effectiveMode);
            if (hit != null) {
                ChatResponse resp = cachedResponse(hit, session, effectiveMode, start);
                session.append(new ChatMessage("user", question));
                session.append(new ChatMessage("assistant", hit.answer()));
                persistence.saveTurn(session, effectiveMode, question, resp);
                return resp;
            }
        } else if (deepTrace || traceEngine) {
            log.info("显式状态图请求绕过缓存，重新验证当前代码. sessionId={}", session.getSessionId());
        }

        ChatResponse resp = doAsk(c, session, effectiveMode, rt, byok, start, hooks);
        // 高质量答案入语义缓存（重复问题秒回；低置信度不缓存——错误放大器）
        if (defaultEngine) {
            semanticCache.store(question, effectiveMode, resp);
        }
        // 阶段一持久化：统一在出口落库（agent/workflow 两引擎全覆盖；失败降级内存不影响回答）
        persistence.saveTurn(session, effectiveMode, question, resp);
        return resp;
    }

    private ChatResponse cachedResponse(SemanticCacheService.CacheHit hit,
                                        ConversationSession session,
                                        String effectiveMode,
                                        long start) {
        long elapsed = System.currentTimeMillis() - start;
        return ChatResponse.builder()
                .answer(hit.answer()).sessionId(session.getSessionId()).mode(effectiveMode)
                .sourceFiles(hit.sourceFiles()).confidence(hit.confidence()).needHuman(false)
                .engine("cache").steps(hit.steps()).stepsUsed(hit.stepsUsed()).cacheHits(hit.hits())
                .completionStatus("complete").elapsedMs(elapsed)
                .performance(ChatResponse.Performance.builder().route("cache").totalMs(elapsed).build())
                .build();
    }

    private ChatResponse doAsk(AskContext c, ConversationSession session, String effectiveMode,
                               LlmRuntime rt, boolean byok, long start,
                               AgentExecutor.StreamHooks hooks) {
        String question = c.getQuestion();

        // 免费额度检查：未自带 Key 且本会话共享额度已用完 => 引导配置自己的 Key
        if (!byok && session.getAskedCount() >= freeQuestions) {
            log.info("免费额度已用完，引导配置自有 Key. sessionId={}, used={}", session.getSessionId(), session.getAskedCount());
            String ans = "本会话的免费体验额度（" + freeQuestions + " 次）已用完。\n\n"
                    + "点击右上角「设置」，选择服务商并填入你自己的 API Key 后即可继续提问。"
                    + "你的 Key 只在本次对话的请求中使用，不会被保存或展示给他人。";
            return ChatResponse.builder()
                    .answer(ans)
                    .sessionId(session.getSessionId())
                    .mode(effectiveMode)
                    .matchedProjects(List.of())
                    .matchedFeatures(List.of())
                    .sourceFiles(List.of())
                    .sources(List.of())
                    .needKey(true)
                    .freeRemaining(0)
                    .confidence("high")
                    .needHuman(false)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .build();
        }
        if (!byok) {
            session.incrAskedCount();
        }
        int remaining = byok ? -1 : Math.max(0, freeQuestions - session.getAskedCount());

        // ===== 智能体引擎分叉（app.agent.enabled 总开关，请求 engine 字段可强制指定）=====
        // analyze 模式（用户已指定项目/接口路径）v1 仍走 workflow 管线
        if (useAgentEngine(c)) {
            if (useTraceEngine(c)) {
                return traceGraphExecutor.execute(c, session, effectiveMode,
                        remaining < 0 ? null : remaining, start, hooks);
            }
            return agentExecutor.execute(c, session, effectiveMode,
                    remaining < 0 ? null : remaining, start, hooks);
        }

        // Step 1: 路由
        // 接口分析模式：用户已指定项目 => 跳过 LLM 路由（省一次调用，且不会被路由误判）
        RouteResult route;
        if (c.getSpecifiedProjects() != null && !c.getSpecifiedProjects().isEmpty()) {
            route = manualRoute(c, question);
            if (route == null) {
                return ChatResponse.builder()
                        .answer("指定的项目不在已配置清单中，请重新选择。可用项目见 /api/projects。")
                        .sessionId(session.getSessionId())
                        .mode(effectiveMode)
                        .matchedProjects(c.getSpecifiedProjects())
                        .matchedFeatures(List.of())
                        .sourceFiles(List.of())
                        .sources(List.of())
                        .confidence("low")
                        .needHuman(false)
                        .elapsedMs(System.currentTimeMillis() - start)
                        .build();
            }
        } else {
            route = router.route(question, session.snapshot(), rt);
            if (route.isNonProductRelated()) {
                log.info("非商品问题，跳过: reason={}", route.getReason());
                String ans = "该问题不属于商品范围，我不处理。原因：" + route.getReason();
                session.append(new ChatMessage("user", question));
                session.append(new ChatMessage("assistant", ans));
                return ChatResponse.builder()
                        .answer(ans)
                        .sessionId(session.getSessionId())
                        .mode(effectiveMode)
                        .matchedProjects(List.of())
                        .matchedFeatures(List.of())
                        .sourceFiles(List.of())
                        .sources(List.of())
                        .confidence("high")
                        .needHuman(false)
                        .elapsedMs(System.currentTimeMillis() - start)
                        .build();
            }
        }

        // Step 2: 取项目对象并检索
        List<ProjectInfo> targetProjects = route.getProjectNames().stream()
                .map(loader::findByName)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<SearchResult> hits = searcher.search(targetProjects, route.getSearchKeywords());

        if (hits.isEmpty()) {
            String ans = "没在你的代码里找到相关实现，可能关键词没命中。\n"
                    + "命中的项目：" + route.getProjectNames() + "\n"
                    + "使用的关键词：" + route.getSearchKeywords();
            session.append(new ChatMessage("user", question));
            session.append(new ChatMessage("assistant", ans));
            return ChatResponse.builder()
                    .answer(ans)
                    .sessionId(session.getSessionId())
                    .mode(effectiveMode)
                    .matchedProjects(route.getProjectNames())
                    .matchedFeatures(route.getFeatureNames())
                    .sourceFiles(List.of())
                    .sources(List.of())
                    .serviceChain(buildServiceChain(route.getProjectNames(), Map.of()))
                    .confidence("low")
                    .needHuman(true)
                    .elapsedMs(System.currentTimeMillis() - start)
                    .build();
        }

        // Step 2b: 引用发现 —— 扫描首轮命中文件的 import，识别路由没覆盖到的下游服务
        // （如 storage-service / search-service），用代码级证据自动补一轮检索。
        Map<String, Set<String>> discoveredRefs = searcher.discoverReferences(
                hits.subList(0, Math.min(hits.size(), 12)), loader.getProjects(), 12);
        List<String> discoveredNames = new ArrayList<>();
        for (String name : discoveredRefs.keySet()) {
            if (!route.getProjectNames().contains(name) && loader.findByName(name) != null) {
                discoveredNames.add(name);
            }
        }
        List<SearchResult> extraHits = List.of();
        if (!discoveredNames.isEmpty()) {
            // 接口分析模式：用户已明确指定项目范围，补搜只用品名度最高的方法名词，
            // 避免把路径泛前缀带到下游项目里匹配出一堆无关 Controller
            boolean analyzeMode = c.getSpecifiedProjects() != null && !c.getSpecifiedProjects().isEmpty();
            List<String> extraKw = route.getSearchKeywords();
            if (analyzeMode && c.getApiPath() != null && c.getApiPath().contains("/")) {
                String lastSeg = c.getApiPath().substring(c.getApiPath().lastIndexOf('/') + 1).trim();
                if (!lastSeg.isBlank()) extraKw = List.of(lastSeg);
            }
            log.info("引用发现，补充检索下游项目: {}, 关键词={}, 依赖边={}", discoveredNames, extraKw, discoveredRefs);
            List<ProjectInfo> extraTargets = discoveredNames.stream()
                    .map(loader::findByName)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            extraHits = searcher.search(extraTargets, extraKw);
        }
        List<SearchResult> mergedHits = mergeWithDiscovered(hits, extraHits, discoveredNames);
        // VO/DTO/常量类信息密度低，统一沉底，把 LLM 名额留给 Controller/Service 实现
        List<SearchResult> orderedHits = demoteLowValueFiles(mergedHits);

        // 接口分析模式：用户已指定项目与路径。若存在方法名级的高具体度命中（pathScore>=60），
        // 则丢弃仅靠类级前缀（pathScore=20）匹配的泛泛 Controller，避免它们挤占精读名额；
        // pathScore=0 的保留（引用发现带出的下游实现等非路径关键词命中）
        if (c.getSpecifiedProjects() != null && !c.getSpecifiedProjects().isEmpty()) {
            boolean hasSpecific = orderedHits.stream().anyMatch(h -> h.getPathScore() >= 60);
            if (hasSpecific) {
                List<SearchResult> focused = new ArrayList<>();
                for (SearchResult h : orderedHits) {
                    if (h.getPathScore() == 0 || h.getPathScore() >= 40) focused.add(h);
                }
                orderedHits = focused;
            }
            // 同项目类引用展开：以锚点入口为起点，把同包 import 的 DTO/Facade/Service 源文件拉进候选
            SearchResult anchor = orderedHits.isEmpty() ? null : orderedHits.get(0);
            if (anchor != null) {
                ProjectInfo ap = loader.findByName(anchor.getProjectName());
                if (ap != null) {
                    List<SearchResult> related = searcher.findSameProjectImports(
                            anchor, ap, projectsProps.getCodeBase(), 4);
                    for (SearchResult r : related) {
                        if (!containsFile(orderedHits, r)) orderedHits.add(r);
                    }
                    log.info("同项目类引用展开: +{} 个关联类", related.size());
                }
            }
        }

        // 响应里的命中项目 = 路由结果 + 引用发现的下游服务
        Set<String> allMatchedNames = new LinkedHashSet<>(route.getProjectNames());
        allMatchedNames.addAll(discoveredNames);
        List<String> matchedProjects = new ArrayList<>(allMatchedNames);

        // 服务链提前构建：上下文头部要标注每个文件的服务角色（入口/下游）
        ChatResponse.ServiceChain chain = buildServiceChain(matchedProjects, discoveredRefs);
        Map<String, String> roleByProject = new LinkedHashMap<>();
        if (chain != null && chain.getNodes() != null) {
            for (ChatResponse.ServiceNode nd : chain.getNodes()) {
                roleByProject.put(nd.getName(), nd.isEntryPoint() ? "链路入口" : "下游依赖");
            }
        }

        // Step 3: 拼代码上下文（围绕命中行截取，而不是只读文件头部）
        // 单项目最多占 2 个精读名额，避免某个下游服务的泛命中文件刷屏
        int maxFiles = Math.min(searchProps.getMaxFilesToLlm(), orderedHits.size());
        Map<String, Integer> projCount = new LinkedHashMap<>();
        List<SearchResult> picked = new ArrayList<>(maxFiles);
        for (SearchResult h : orderedHits) {
            if (picked.size() >= maxFiles) break;
            int cnt = projCount.merge(h.getProjectName(), 1, Integer::sum);
            if (cnt <= 2) picked.add(h);
        }
        for (SearchResult h : orderedHits) {
            if (picked.size() >= maxFiles) break;
            if (!containsFile(picked, h)) picked.add(h);
        }
        StringBuilder ctx = new StringBuilder();
        List<String> sourceFiles = new ArrayList<>(picked.size());
        List<ChatResponse.SourceRef> sources = new ArrayList<>(picked.size());
        int fileNo = 1;
        for (SearchResult h : picked) {
            String relPath = toRelative(h.getFilePath());
            ctx.append("==== 文件 #").append(fileNo++).append(" ====\n");
            ctx.append("路径: ").append(relPath).append('\n');
            ctx.append("项目: ").append(h.getProjectName());
            if (roleByProject.containsKey(h.getProjectName())) {
                ctx.append("（服务角色：").append(roleByProject.get(h.getProjectName())).append("）");
            }
            ctx.append('\n');
            ctx.append("命中关键词: ").append(h.getMatchedKeyword())
                    .append(" (×").append(h.getHitCount()).append(")");
            if (h.isPathHit()) ctx.append(" [精确路径命中]");
            if (h.isPartialPathHit()) ctx.append(" [路径片段命中]");
            if (h.getRouteAnnotationCount() > 0) ctx.append(" [Controller, ann=").append(h.getRouteAnnotationCount()).append("]");
            ctx.append('\n');
            ctx.append("---- 内容（方法级截取）----\n");
            ctx.append(searcher.readCodeForLlm(h.getFilePath(), h.getHitLines(), searchProps.getMaxLinesPerFile()));
            ctx.append('\n');
            sourceFiles.add(relPath);
            // 入口判定：含路由注解或本身是 Controller 类。
            // 注意不能用 pathHit 判断：普通类里出现 URL 字符串（如常量定义）也会 pathHit
            boolean entry = h.getRouteAnnotationCount() > 0 || relPath.endsWith("Controller.java");
            sources.add(ChatResponse.SourceRef.builder()
                    .project(h.getProjectName())
                    .file(relPath)
                    .lines(sampleLines(h.getHitLines()))
                    .keyword(h.getMatchedKeyword())
                    .entry(entry)
                    .build());
        }

        // Step 4: 调 LLM 答题
        // dev 模式走两阶段：先让模型只依据代码产出"事实底稿"（类/方法/行号/分支/数据源），
        // 再按固定骨架成文。避免一次生成既要组织结构又要挖细节导致的散乱。
        String historyBlock = renderHistory(session);
        String llmResp;
        if ("dev".equals(effectiveMode)) {
            String factSystem = """
                    你是 Java 代码分析引擎。基于提供的代码片段输出"事实清单"，不要写成给用户的回答，不要客套和总结。
                    要求：
                    1) 按 文件 -> 相关类/方法（含真实行号）列出每个文件里与问题相关的实现点；
                    2) 列出能从代码确认的调用关系，每条一行：调用方类#方法 (文件:行号) -> 被调方类#方法 (文件:行号)；
                    3) 列出分支/开关条件及判断依据（引用真实字段名、常量、配置类，带行号）；
                    4) 列出数据源与中间件：库名/表名/缓存 key/ES 索引/MQ topic（带行号）；
                    5) 片段里没有的信息明确写"未检索到"，禁止推测或补全。
                    """;
            String goalLine = (c.getIntent() != null && !c.getIntent().isBlank())
                    ? "\n用户的分析目标（事实收集时优先覆盖）：" + c.getIntent() : "";
            String factUser = "用户问题：" + question + goalLine
                    + "\n\n涉及项目：" + matchedProjects
                    + "\n\n检索到的代码：\n" + ctx + "\n\n请输出事实清单。";
            String facts = llm.chat(factSystem, factUser, rt);
            log.debug("dev 两阶段事实底稿:\n{}", facts);

            String system = c.useFlowchart() ? buildDevSystemPrompt(route) : buildDevNoFlowchartPrompt(route);
            String goalSuffix = (c.getIntent() != null && !c.getIntent().isBlank())
                    ? "\n用户的分析目标：" + c.getIntent() : "";
            String user = buildFinalDevUserPrompt(question + goalSuffix, route, facts, historyBlock);
            llmResp = llm.chat(system, user, rt);
        } else {
            String system = buildUserSystemPrompt(route);
            String user = buildAnswerUserPrompt(question, route, ctx.toString(), historyBlock, effectiveMode);
            llmResp = llm.chat(system, user, rt);
        }

        String[] parsed = parseConfidence(llmResp);
        String answer = parsed[0];
        String confidence = parsed[1];
        boolean needHuman = "low".equalsIgnoreCase(confidence);

        // 记录本轮（answer 已剥掉置信度尾标）
        session.append(new ChatMessage("user", question));
        session.append(new ChatMessage("assistant", answer));

        long elapsed = System.currentTimeMillis() - start;
        log.info("=== 回答完成，耗时={}ms, confidence={}, 文件数={}, mode={} ===",
                elapsed, confidence, sourceFiles.size(), effectiveMode);

        return ChatResponse.builder()
                .answer(answer)
                .sessionId(session.getSessionId())
                .mode(effectiveMode)
                .matchedProjects(matchedProjects)
                .matchedFeatures(route.getFeatureNames())
                .sourceFiles(sourceFiles)
                .sources(sources)
                .serviceChain(chain)
                .confidence(confidence)
                .needHuman(needHuman)
                .needKey(false)
                .freeRemaining(remaining < 0 ? null : remaining)
                .elapsedMs(elapsed)
                .performance(ChatResponse.Performance.builder().route("workflow").totalMs(elapsed).build())
                .build();
    }

    /**
     * 首轮 + 引用发现补搜的结果合并：
     * 原排序保留大部分名额，每个新发现项目保底带 1 个最佳文件，其余按序追加（后续仍按 max-files-to-llm 截断）。
     */
    private List<SearchResult> mergeWithDiscovered(List<SearchResult> base,
                                                   List<SearchResult> extra,
                                                   List<String> discoveredNames) {
        if (extra.isEmpty() || discoveredNames.isEmpty()) return base;
        int maxFiles = searchProps.getMaxFilesToLlm();
        int reserve = Math.min(discoveredNames.size(), 3);                       // 新项目保底名额
        int keepBase = Math.min(base.size(), Math.max(2, maxFiles - reserve));
        List<SearchResult> out = new ArrayList<>(base.subList(0, keepBase));
        for (String name : discoveredNames) {
            extra.stream().filter(h -> name.equals(h.getProjectName())).findFirst()
                    .ifPresent(h -> { if (!containsFile(out, h)) out.add(h); });
        }
        for (SearchResult h : extra) {
            if (!containsFile(out, h)) out.add(h);
        }
        for (int i = keepBase; i < base.size(); i++) {
            SearchResult h = base.get(i);
            if (!containsFile(out, h)) out.add(h);
        }
        return out;
    }

    private boolean containsFile(List<SearchResult> list, SearchResult r) {
        return list.stream().anyMatch(x -> x.getFilePath().equals(r.getFilePath()));
    }

    /** VO/DTO/常量/枚举等声明类文件信息密度低，沉到候选列表末尾 */
    private List<SearchResult> demoteLowValueFiles(List<SearchResult> hits) {
        List<SearchResult> core = new ArrayList<>();
        List<SearchResult> low = new ArrayList<>();
        for (SearchResult h : hits) {
            String f = h.getFilePath();
            boolean isLow = f.matches(".*\\w+(VO|DTO|Constants?|Enum|Exception|Bean|Builder|Utils?|Config)\\.java$")
                    || f.contains("/vo/") || f.contains("/dto/") || f.contains("/constant");
            (isLow ? low : core).add(h);
        }
        List<SearchResult> out = new ArrayList<>(core);
        out.addAll(low);
        return out;
    }

    /**
     * 由 projects.yml 的静态依赖关系（rpc_to / called_by）+ 引用发现的代码级依赖边，
     * 构建本次回答涉及的服务级调用链。只保留命中项目集合内部的边，避免图被无关服务撑爆。
     *
     * @param discoveredRefs 引用发现结果：key=下游项目, value=调用方项目集合；可为空
     */
    private ChatResponse.ServiceChain buildServiceChain(List<String> projectNames,
                                                        Map<String, Set<String>> discoveredRefs) {
        if (projectNames == null || projectNames.isEmpty()) return null;
        Set<String> names = new LinkedHashSet<>(projectNames);

        List<ChatResponse.ServiceNode> nodes = new ArrayList<>();
        for (String name : names) {
            ProjectInfo p = loader.findByName(name);
            nodes.add(ChatResponse.ServiceNode.builder()
                    .name(name)
                    .description(p == null ? "" : p.getDescription())
                    .entryPoint(false)
                    .build());
        }

        // 收集集合内部的调用边（rpc_to 正向 + called_by 反向 + 引用发现，同一对去重）
        List<ChatResponse.ServiceEdge> edges = new ArrayList<>();
        Set<String> edgeKeys = new LinkedHashSet<>();
        for (String from : names) {
            ProjectInfo p = loader.findByName(from);
            if (p != null && p.getRpcTo() != null) {
                for (String to : p.getRpcTo()) {
                    if (names.contains(to) && !from.equals(to) && edgeKeys.add(from + "->" + to)) {
                        edges.add(ChatResponse.ServiceEdge.builder().from(from).to(to).type("rpc").build());
                    }
                }
            }
            if (p != null && p.getCalledBy() != null) {
                for (String caller : p.getCalledBy()) {
                    if (names.contains(caller) && !caller.equals(from) && edgeKeys.add(caller + "->" + from)) {
                        edges.add(ChatResponse.ServiceEdge.builder().from(caller).to(from).type("rpc").build());
                    }
                }
            }
        }
        // 引用发现的边：from=调用方（命中文件所在项目），to=被发现的项目
        if (discoveredRefs != null) {
            for (Map.Entry<String, Set<String>> e : discoveredRefs.entrySet()) {
                String to = e.getKey();
                if (!names.contains(to)) continue;
                for (String from : e.getValue()) {
                    if (names.contains(from) && !from.equals(to) && edgeKeys.add(from + "->" + to)) {
                        edges.add(ChatResponse.ServiceEdge.builder().from(from).to(to).type("rpc").build());
                    }
                }
            }
        }

        // 入口点 = 集合内没有上游的节点
        Set<String> hasUpstream = new LinkedHashSet<>();
        for (ChatResponse.ServiceEdge e : edges) hasUpstream.add(e.getTo());
        for (ChatResponse.ServiceNode n : nodes) {
            n.setEntryPoint(!hasUpstream.contains(n.getName()));
        }

        if (nodes.size() < 2 && edges.isEmpty()) return null;
        return ChatResponse.ServiceChain.builder().nodes(nodes).edges(edges).build();
    }

    /** 接口分析模式：用户指定项目时手工构造路由结果，跳过 LLM 路由 */
    private RouteResult manualRoute(AskContext c, String question) {
        Set<String> known = loader.getProjects().stream()
                .map(ProjectInfo::getName)
                .collect(java.util.stream.Collectors.toSet());
        List<String> picked = c.getSpecifiedProjects().stream()
                .filter(known::contains)
                .distinct()
                .toList();
        if (picked.isEmpty()) return null;
        List<String> kws = new ArrayList<>();
        if (c.getApiPath() != null && !c.getApiPath().isBlank()) {
            kws.add(c.getApiPath().trim());
        }
        Matcher m = URL_IN_TEXT.matcher(question);
        while (m.find()) {
            if (!kws.contains(m.group())) kws.add(m.group());
        }
        return RouteResult.builder()
                .question(question)
                .projectNames(new ArrayList<>(picked))
                .featureNames(List.of())
                .searchKeywords(kws)
                .nonProductRelated(false)
                .reason("接口分析模式：用户指定项目")
                .build();
    }

    private static final Pattern URL_IN_TEXT =
            Pattern.compile("/[a-zA-Z][\\w-]*(?:/[\\w-]+)+");

    /** 绝对路径转相对 code-base 的短路径（正斜杠），失败时原样返回 */
    private String toRelative(String filePath) {
        try {
            Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
            Path p = Paths.get(filePath).toAbsolutePath().normalize();
            return base.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return filePath.replace('\\', '/');
        }
    }

    /** 命中行号最多展示 30 个，防止响应过大 */
    private List<Integer> sampleLines(List<Integer> hitLines) {
        if (hitLines == null || hitLines.size() <= 30) return hitLines;
        return hitLines.subList(0, 30);
    }

    /** 渲染历史消息为 LLM 可读的对话块 */
    private String renderHistory(ConversationSession session) {
        List<ChatMessage> hist = session.snapshot();
        if (hist.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : hist) {
            sb.append("  ").append("user".equals(m.getRole()) ? "用户" : "AI").append("：")
                    .append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    private String buildAnswerSystemPrompt(RouteResult route, String mode) {
        if ("dev".equals(mode)) {
            return buildDevSystemPrompt(route);
        }
        return buildUserSystemPrompt(route);
    }

    /** user 模式：面向业务用户，口语化，禁标题 */
    private String buildUserSystemPrompt(RouteResult route) {
        return """
                你是商品研发团队的内部 AI 助手，像组里熟代码的同事在企微群里回答提问一样，自然、直接、口语化。

                内容要求：
                1) 严格基于提供的代码内容回答，不要编造不存在的类、方法、字段。
                2) 第一句话先给直接结论（接口在哪、谁来处理、走什么数据源），不要先铺垫"基于提供的代码…"。
                3) 讲关键逻辑时要落到具体实现：方法名、核心分支判断、数据源（库/表/缓存/MQ），并给出文件引用。
                4) 涉及调用链路时，按 入口 → 处理 → 数据层 的顺序讲，可以用箭头 → 串起来。
                5) 文件引用直接内联在句子里，格式：[相对路径:行号]，例如 [PersonalAlbumController.java:205]。
                   不要单独列"参考文件"段落，也不要把引用当成段落结尾的点缀。
                6) 代码不足以回答时，直接说"这批代码里没看到 xxx，建议补充 yyy"，不要硬答。

                格式要求（重要）：
                - 像聊天一样写自然段，用换行分段；不要用 ## / ### 这类标题把回答切成 wiki 文档。
                - 短列表可以用 "-" 列表项；超过两层嵌套的子标题（### 1. / ### 2.）一律不要。
                - 类名、方法名、字段名用反引号包裹，例如 `personalAllCommon()`。
                - 整体长度控制在 300-600 字，复杂链路可以写到 900 字；
                  信息密度优先：关键细节（分支条件、数据源、行号）不能为了压字数而砍掉，废话则能砍则砍。

                多轮对话要求：
                - 上下文里给了"历史对话"，如果用户问题里出现"上面 / 刚刚 / 那个"等指代，结合历史作答。
                - 历史里没出现过的代码细节，仍以本轮检索到的代码为准。

                回答最末尾追加一行置信度标记，格式严格为：
                [CONFIDENCE:high] 或 [CONFIDENCE:medium] 或 [CONFIDENCE:low]
                  - high: 代码完整且能解释清楚
                  - medium: 部分相关，推断成分较多
                  - low: 信息不足，建议转人工

                当前命中项目：""" + route.getProjectNames();
    }

    /** dev 模式：面向研发排查，强制 mermaid 流程图开头 */
    private String buildDevSystemPrompt(RouteResult route) {
        return """
                你是商品研发团队的内部 AI 助手，当前处于"开发者排查模式"。提问者是工程师，目的是搞清楚调用链路、定位问题、看代码细节。
                你会拿到一份「已核实的代码事实清单」，它是唯一事实来源；你的任务是把清单组织成一份结构清晰、可追溯的排查文档。

                内容要求：
                1) 严格基于事实清单回答，不要编造清单里不存在的类、方法、行号。
                2) 每一跳都要讲透：方法签名、关键分支判断（引用真实字段名）、数据源（库/表/缓存/MQ）。
                3) 代码不足以回答的环节，明确说"该环节代码未检索到"，并给出建议补充的方向，不要硬答。

                格式要求（重要，必须严格遵守）：
                1) 回答开头必须先输出一个 mermaid 流程图代码块（用 ```mermaid 包裹），把调用链路画出来。
                   **节点 label 必须短**，否则渲染会失败或乱成一团。硬性规则：
                   - 每个 label 不超过 30 个字符，超过就砍
                   - label 内容只用：方法名（不带括号）+ 文件:行号；细节描述搬到流程图下面的正文
                   - 不要在 label 里出现 () / {} / [] 这种裸符号（会被 Mermaid 当语法）
                   - 数据源/库类型可以单独画一个节点，label 只写 "ES" 或 "MongoDB" 或 "Redis"
                   - 分支用菱形 {} 节点，里面只写判断条件的短标签，例如 {"newSearch?"}
                   - 用 flowchart TD（自上而下）
                2) 流程图之后严格按下面的逐跳模板展开，一跳一段，顺序与流程图一致：

                   **第 N 跳：所属服务｜`类名#方法名`**（`文件相对路径:起始行-结束行`）
                   - 职责：这一步做什么（一句话）
                   - 关键逻辑：入参校验 / 分支条件 / 循环 / 事务等硬细节，必须引用代码里的真实字段与方法名
                   - 输出去向：调用了下一跳的谁；写哪个库表 / 缓存 key / MQ topic / ES 索引

                3) 所有跳写完后必须附一张 Markdown 汇总表（不可省略）：
                   | 层级 | 服务 | 关键类#方法 | 数据源 | 代码位置 |
                   层级取值：入口 / 业务 / 数据 / 搜索 等。
                4) 类名/方法名/字段名/表名一律用反引号包裹；不要使用 ## 大标题。
                5) 长度不设死上限，以把每一跳讲透为准（通常 600-1500 字）；禁止复述用户问题、禁止注水。

                多轮对话要求：
                - 看到"历史对话"里有指代（上面 / 刚刚 / 那段），优先复用历史结论，再结合本轮检索补充。
                - 历史里没出现的代码细节，以本轮检索为准。

                回答最末尾追加一行置信度标记，格式严格为：
                [CONFIDENCE:high] 或 [CONFIDENCE:medium] 或 [CONFIDENCE:low]
                  - high: 代码完整且能解释清楚
                  - medium: 部分相关，推断成分较多
                  - low: 信息不足，建议转人工

                当前命中项目：""" + route.getProjectNames();
    }

    /** dev 成文阶段：不再重复喂代码，以事实底稿为唯一依据 */
    private String buildFinalDevUserPrompt(String question, RouteResult route,
                                           String facts, String historyBlock) {
        return """
                用户问题：%s

                路由信息：
                - 命中项目：%s
                - 命中功能：%s

                历史对话（可能为空）：
                %s

                已核实的代码事实清单（唯一可信来源，禁止引入清单之外的类/方法/行号）：
                %s

                请按系统指令的固定骨架成文：mermaid 流程图 -> 逐跳模板 -> 汇总表 -> 末尾 [CONFIDENCE:xxx]。
                """.formatted(question, route.getProjectNames(), route.getFeatureNames(),
                historyBlock.isEmpty() ? "(无)" : historyBlock, facts);
    }

    /** dev 模式无流程图变体：结构与标准 dev 一致，但不要求 mermaid（省 token） */
    private String buildDevNoFlowchartPrompt(RouteResult route) {
        return """
                你是商品研发团队的内部 AI 助手，当前处于"开发者排查模式"。提问者是工程师，目的是搞清楚调用链路、定位问题、看代码细节。
                你会拿到一份「已核实的代码事实清单」，它是唯一事实来源；你的任务是把清单组织成一份结构清晰、可追溯的排查文档。
                本次回答不生成 mermaid 流程图，直接按逐跳模板展开。

                内容要求：
                1) 严格基于事实清单回答，不要编造清单里不存在的类、方法、行号。
                2) 每一跳都要讲透：方法签名、关键分支判断（引用真实字段名）、数据源（库/表/缓存/MQ）。
                3) 代码不足以回答的环节，明确说"该环节代码未检索到"，并给出建议补充的方向，不要硬答。

                格式要求（必须严格遵守）：
                1) 按下面的逐跳模板展开，一跳一段，从入口到数据源：

                   **第 N 跳：所属服务｜`类名#方法名`**（`文件相对路径:起始行-结束行`）
                   - 职责：这一步做什么（一句话）
                   - 关键逻辑：入参校验 / 分支条件 / 循环 / 事务等硬细节，必须引用代码里的真实字段与方法名
                   - 输出去向：调用了下一跳的谁；写哪个库表 / 缓存 key / MQ topic / ES 索引

                2) 所有跳写完后必须附一张 Markdown 汇总表（不可省略）：
                   | 层级 | 服务 | 关键类#方法 | 数据源 | 代码位置 |
                   层级取值：入口 / 业务 / 数据 / 搜索 等。
                3) 类名/方法名/字段名/表名一律用反引号包裹；不要使用 ## 大标题。
                4) 长度不设死上限，以把每一跳讲透为准；禁止复述用户问题、禁止注水。

                多轮对话要求：
                - 看到"历史对话"里有指代（上面 / 刚刚 / 那段），优先复用历史结论，再结合本轮检索补充。
                - 历史里没出现的代码细节，以本轮检索为准。

                回答最末尾追加一行置信度标记，格式严格为：
                [CONFIDENCE:high] 或 [CONFIDENCE:medium] 或 [CONFIDENCE:low]
                  - high: 代码完整且能解释清楚
                  - medium: 部分相关，推断成分较多
                  - low: 信息不足，建议转人工

                当前命中项目：""" + route.getProjectNames();
    }

    private String buildAnswerUserPrompt(String question, RouteResult route,
                                         String codeContext, String historyBlock, String mode) {
        return """
                用户问题：%s

                路由分析：
                - 命中项目: %s
                - 命中功能: %s
                - 检索关键词: %s

                历史对话（最近若干轮，可能为空）：
                %s

                检索到的代码片段：
                %s

                请按 %s 模式的格式要求回答用户问题，并在末尾追加 [CONFIDENCE:xxx] 标记。
                """.formatted(
                        question,
                        route.getProjectNames(),
                        route.getFeatureNames(),
                        route.getSearchKeywords(),
                        historyBlock,
                        codeContext,
                        mode
                );
    }

    /**
     * 引擎选择：请求 engine 字段（agent/workflow）优先，缺省跟随 app.agent.enabled 配置。
     */
    private boolean useAgentEngine(AskContext c) {
        String e = c.getEngine() == null ? "" : c.getEngine().trim().toLowerCase();
        if ("agent".equals(e) || "trace".equals(e) || "legacy".equals(e)) return true;
        if ("workflow".equals(e)) return false;
        // 接口分析（指定项目/接口路径）无条件走 agent：workflow 的静态 import 展开跳不过
        // "接口→实现类"（接口文件不含指向 impl 的引用），agent 可以主动搜 implements XXX 补齐。
        // 2026-08-31 真实案例：/api/v3/query/detail 两轮都停在"实现类未检索到"（low 置信度）。
        if (c.getApiPath() != null && !c.getApiPath().isBlank()) return true;
        if (c.getSpecifiedProjects() != null && !c.getSpecifiedProjects().isEmpty()) return true;
        return agentProps.isEnabled();
    }

    /**
     * 新旧执行器双轨选择。hybrid 只把需要跨服务闭环的任务交给 trace，
     * 普通 FAQ/简单代码问答继续使用已经验证过的 legacy 循环。
     */
    private boolean useTraceEngine(AskContext c) {
        String request = c.getEngine() == null ? "" : c.getEngine().trim().toLowerCase();
        if ("trace".equals(request)) return true;
        if ("legacy".equals(request)) return false;
        String configured = agentProps.getExecutor() == null
                ? "hybrid" : agentProps.getExecutor().trim().toLowerCase();
        if ("trace".equals(configured)) return true;
        if ("legacy".equals(configured)) return false;
        return agentProps.isDeepTraceRequest(c.getQuestion(), c.getIntent())
                || (c.getApiPath() != null && !c.getApiPath().isBlank())
                || (c.getSpecifiedProjects() != null && !c.getSpecifiedProjects().isEmpty());
    }

    /** 切分正文和末尾置信度标记 */
    private String[] parseConfidence(String resp) {
        if (resp == null) return new String[]{"", "low"};
        int idx = resp.lastIndexOf("[CONFIDENCE:");
        if (idx < 0) return new String[]{resp.trim(), "medium"};
        String body = resp.substring(0, idx).trim();
        String tail = resp.substring(idx);
        String confidence = "medium";
        String t = tail.toLowerCase();
        if (t.contains("high")) confidence = "high";
        else if (t.contains("low")) confidence = "low";
        else if (t.contains("medium")) confidence = "medium";
        return new String[]{body, confidence};
    }
}
