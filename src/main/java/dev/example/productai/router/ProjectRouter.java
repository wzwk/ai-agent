package dev.example.productai.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.llm.LlmClient;
import dev.example.productai.llm.LlmRuntime;
import dev.example.productai.model.ChatMessage;
import dev.example.productai.model.FeatureInfo;
import dev.example.productai.model.ProjectInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 路由器：根据用户问题，决定检索哪些项目、哪些 feature、用什么关键词
 * 走 LLM 做语义匹配（一次调用约 200-500 token）
 *
 * 输出 JSON：
 * {
 *   "projects": ["sample-album-bff", "sample-moments-service"],
 *   "features": ["动态页查询"],
 *   "keywords": ["动态", "moments", "feed"],
 *   "non_product": false,
 *   "reason": "..."
 * }
 
 * @author wengzewei*/
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectRouter {

    private final LlmClient llm;
    private final ProjectsConfigLoader loader;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 抽取问题里的 URL 路径，例如 /personal/all、/rpc/album/personal/all */
    private static final Pattern URL_PATH_PATTERN =
            Pattern.compile("/[a-zA-Z][\\w-]*(?:/[\\w-]+)+");

    public RouteResult route(String question) {
        return route(question, Collections.emptyList(), null);
    }

    /**
     * 带历史对话的路由。
     * 多轮场景下，用户可能问"上面那个 ES 怎么实现"、"上次回答太简单了，要更详细的流程"这类纯追问，
     * 当前问题本身不含任何业务关键词。如果路由器看不到历史，会把追问误判为非商品问题。
     * 因此把近期历史一并交给路由器，让它结合上下文判断真实意图。
     *
     * @param rt LLM 运行时覆盖（访客自带 Key）；可为 null
     */
    public RouteResult route(String question, List<ChatMessage> history, LlmRuntime rt) {
        List<ProjectInfo> all = loader.getProjects();
        if (all.isEmpty()) {
            return RouteResult.builder()
                    .question(question)
                    .nonProductRelated(true)
                    .reason("未配置任何项目")
                    .build();
        }

        String prompt = buildRouterPrompt(question, all, history);
        String system = """
                你是代码问答助手的项目路由器。
                任务：根据用户当前问题（必要时结合近期对话历史），从已知项目清单中挑选最相关的 1-3 个项目，并给出代码检索关键词。

                多轮对话处理（重要）：
                - 当前要路由的永远是"用户当前问题"这一句，历史只是上下文参考。
                - 如果当前问题像是追问（"上面 / 上次 / 继续 / 详细 / 那个 / 补充 / 完整 / 还有"等指代词），必须结合历史判断其真实意图：用户实际想问的是什么项目、什么功能、什么关键词？
                - 这种情况下，projects 应该跟上一轮保持一致（用户没切话题），keywords 应该反映追问的具体方面（如"ES 查询"、"权限校验"、"rpc 调用"等）。
                - 历史里没出现过的项目，不要无中生有。

                必须严格输出 JSON，不要输出任何解释、Markdown 标记或多余字符。
                输出格式：
                {
                  "projects": ["项目name1"],
                  "features": ["feature名（来自 sample-album-bff 等，没有就空数组）"],
                  "keywords": ["关键词1", "关键词2"],
                  "non_product": false,
                  "reason": "简短中文说明"
                }
                如果当前问题（即便结合历史后）与商品/库存/订单/动态/装修/价格等业务无关，non_product 设为 true，projects 与 features 留空数组。
                """;

        String resp = llm.chat(system, prompt, rt);
        log.debug("路由器 LLM 原始返回:\n{}", resp);
        return parseRouterResponse(question, resp, all);
    }

    private String buildRouterPrompt(String question, List<ProjectInfo> all, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("已知项目清单：\n");
        for (ProjectInfo p : all) {
            sb.append("- name=").append(p.getName());
            sb.append(" | tags=").append(p.getTags() == null ? "[]" : p.getTags());
            sb.append(" | desc=").append(p.getDescription() == null ? "" : p.getDescription());
            if (p.getFeatures() != null && !p.getFeatures().isEmpty()) {
                sb.append(" | features=");
                String fs = p.getFeatures().stream()
                        .map(f -> f.getName() + "(keywords=" + f.getKeywords() + ")")
                        .collect(Collectors.joining(", "));
                sb.append(fs);
            }
            sb.append('\n');
        }

        // 近期对话历史（截断每条避免 prompt 膨胀）
        if (history != null && !history.isEmpty()) {
            sb.append("\n近期对话历史（仅供理解上下文，不要直接照搬历史里的项目名/关键词）:\n");
            int idx = 1;
            for (ChatMessage m : history) {
                String role = "user".equals(m.getRole()) ? "用户" : "AI";
                String c = m.getContent() == null ? "" : m.getContent();
                if (c.length() > 300) c = c.substring(0, 300) + "...";
                sb.append("  [").append(idx++).append("] ").append(role).append(": ")
                        .append(c.replace('\n', ' ')).append('\n');
            }
        }

        sb.append("\n用户当前问题：").append(question).append('\n');
        sb.append("\n请输出 JSON：");
        return sb.toString();
    }

    private RouteResult parseRouterResponse(String question, String resp, List<ProjectInfo> all) {
        try {
            String json = extractJson(resp);
            JsonNode root = mapper.readTree(json);

            List<String> projectNames = readStringArray(root.get("projects"));
            List<String> featureNames = readStringArray(root.get("features"));
            List<String> keywords = readStringArray(root.get("keywords"));
            boolean nonProduct = root.path("non_product").asBoolean(false);
            String reason = root.path("reason").asText("");

            // 校正：如果 non_product=true，直接返回
            if (nonProduct) {
                return RouteResult.builder()
                        .question(question)
                        .projectNames(Collections.emptyList())
                        .featureNames(Collections.emptyList())
                        .searchKeywords(Collections.emptyList())
                        .nonProductRelated(true)
                        .reason(reason)
                        .build();
            }

            // 过滤掉未知项目名
            Set<String> known = all.stream().map(ProjectInfo::getName).collect(Collectors.toSet());
            List<String> filtered = projectNames.stream()
                    .filter(known::contains)
                    .distinct()
                    .toList();
            if (filtered.isEmpty()) {
                // 一个都没匹配上，退化为"非商品问题"
                return RouteResult.builder()
                        .question(question)
                        .projectNames(Collections.emptyList())
                        .featureNames(Collections.emptyList())
                        .searchKeywords(Collections.emptyList())
                        .nonProductRelated(true)
                        .reason("路由器返回的项目名均不在已知清单内: " + projectNames + "; " + reason)
                        .build();
            }

            // 展开 RPC 依赖（多跳）：从命中项目出发，沿项目级/feature 级 rpc_to 与 called_by 反复扩展，
            // 直到集合不再增长。例如 sample-album-bff → dasbizpersonal → core-service/commoditysearch。
            Set<String> expanded = new LinkedHashSet<>(filtered);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (ProjectInfo p : all) {
                    if (!expanded.contains(p.getName())) continue;
                    if (p.getRpcTo() != null) {
                        for (String t : p.getRpcTo()) {
                            if (!t.equals(p.getName()) && known.contains(t) && expanded.add(t)) changed = true;
                        }
                    }
                    if (p.getCalledBy() != null) {
                        for (String u : p.getCalledBy()) {
                            if (!u.equals(p.getName()) && known.contains(u) && expanded.add(u)) changed = true;
                        }
                    }
                    if (p.getFeatures() != null) {
                        for (FeatureInfo f : p.getFeatures()) {
                            if (featureNames.contains(f.getName()) && f.getRpcTo() != null) {
                                for (String t : f.getRpcTo()) {
                                    if (!t.equals(p.getName()) && known.contains(t) && expanded.add(t)) changed = true;
                                }
                            }
                        }
                    }
                }
            }

            // 合并关键词：
            //   1) 用户问题里抽出的 URL 路径（最高优先级，精确匹配用）
            //   2) LLM 给的语义关键词
            //   3) 命中项目的 tags + feature keywords
            List<String> urlPaths = extractUrlPaths(question);
            Set<String> mergedKw = new LinkedHashSet<>(urlPaths);
            mergedKw.addAll(keywords);
            for (ProjectInfo p : all) {
                if (!expanded.contains(p.getName())) continue;
                if (p.getTags() != null) mergedKw.addAll(p.getTags());
                if (p.getFeatures() != null) {
                    for (FeatureInfo f : p.getFeatures()) {
                        if (featureNames.contains(f.getName()) && f.getKeywords() != null) {
                            mergedKw.addAll(f.getKeywords());
                        }
                    }
                }
            }
            // 过滤掉太短或太长的词；URL 路径（以 / 开头）不受长度限制
            List<String> finalKw = mergedKw.stream()
                    .filter(Objects::nonNull)
                    .filter(s -> s.startsWith("/") || (s.length() >= 2 && s.length() <= 20))
                    .toList();

            log.info("路由结果: projects={}, features={}, keywords={}, expanded={}",
                    expanded, featureNames, finalKw, expanded.size() > filtered.size());

            return RouteResult.builder()
                    .question(question)
                    .projectNames(new ArrayList<>(expanded))
                    .featureNames(featureNames)
                    .searchKeywords(finalKw)
                    .nonProductRelated(false)
                    .reason(reason)
                    .build();
        } catch (Exception e) {
            log.warn("解析路由器返回失败，降级为非商品问题。raw={}", resp, e);
            return RouteResult.builder()
                    .question(question)
                    .nonProductRelated(true)
                    .reason("解析失败: " + e.getMessage())
                    .build();
        }
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>(node.size());
        for (JsonNode n : node) {
            String s = n.asText("");
            if (!s.isBlank()) list.add(s.trim());
        }
        return list;
    }

    /**
     * 从用户原问题里抽 URL 路径，例如 "/personal/all"、"/rpc/album/personal/all"。
     * 这些路径会作为最高优先级的检索关键词，直接用来在源码里精确定位 Controller。
     * 要求至少两段（即中间必须有一个 /），避免误捕日期 "2024/01/01" 之外的单段短横线。
     */
    private List<String> extractUrlPaths(String question) {
        if (question == null || question.isEmpty()) return Collections.emptyList();
        Matcher m = URL_PATH_PATTERN.matcher(question);
        Set<String> paths = new LinkedHashSet<>();
        while (m.find()) {
            paths.add(m.group());
        }
        return new ArrayList<>(paths);
    }

    /** 防止 LLM 输出多余的 ```json 包裹 */
    private String extractJson(String text) {
        if (text == null) return "{}";
        String t = text.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first > 0 && last > first) {
                t = t.substring(first + 1, last).trim();
            }
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            t = t.substring(start, end + 1);
        }
        return t;
    }
}
