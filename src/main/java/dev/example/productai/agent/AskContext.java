package dev.example.productai.agent;

import dev.example.productai.llm.LlmRuntime;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次问答的完整上下文参数。
 * 普通聊天只填 question/mode/sessionId/rt；
 * 接口分析模块额外指定 projects/apiPath/intent/flowchart，可跳过 LLM 路由。
 
 * @author wengzewei*/
@Data
@Builder
public class AskContext {
    private static final Pattern API_PATH_IN_TEXT =
            Pattern.compile("/[a-zA-Z][\\w-]*(?:/[\\w-]+)+");
    private static final Pattern MERMAID_BLOCK =
            Pattern.compile("```\\s*mermaid\\s*[\\s\\S]*?```", Pattern.CASE_INSENSITIVE);

    private String question;
    private String mode;
    private String sessionId;
    private LlmRuntime rt;

    /** 指定检索的项目名列表；非空时跳过 LLM 路由，直接在这些项目内检索 */
    private List<String> specifiedProjects;

    /** 项目 key 从问题文本识别而来；只限定检索范围，不因此把普通问答升级为 trace。 */
    private boolean projectScopeInferred;

    /** 接口路径，如 /api/v1/items/save；作为最高优先级检索关键词 */
    private String apiPath;

    /** 自动定位出的唯一服务端入口文件；仅供执行器限定调用链根节点，不由前端填写。 */
    private String entryFile;

    /** 用户自定义分析目标：梳理逻辑 / 排查报错 / 评估改动影响等 */
    private String intent;

    /** dev 模式是否生成 mermaid 流程图；显式传值优先，否则从用户原话判断，默认不生成。 */
    private Boolean flowchart;

    /** 引擎指定：null/空 = 跟随配置；workflow/agent/legacy/trace 可用于评测和一键回退。 */
    private String engine;

    public boolean useFlowchart() {
        if (flowchart != null) return flowchart;
        String text = responsePreferenceText();
        if (containsAny(text, "不要画图", "不用画图", "别画图", "无需画图", "不需要画图",
                "不要画流程图", "不用画流程图", "无需画流程图", "不需要画流程图",
                "不要流程图", "不用流程图", "无流程图", "不要mermaid", "不用mermaid")) {
            return false;
        }
        return containsAny(text, "画个图", "画一张图", "画流程图", "流程图", "架构图", "时序图",
                "mermaid", "diagram");
    }

    /** 用户明确要求短答案或只给某类结论时，不能再套开发者长报告模板。 */
    public boolean prefersConciseAnswer() {
        String text = responsePreferenceText();
        return containsAny(text, "简洁", "简短", "一句话", "只要告诉", "只告诉", "直接告诉",
                "只说", "只需要", "不用展开", "无需展开", "不要展开", "不要详细", "不用详细",
                "只给结论", "只要结论", "只给配置", "只要配置");
    }

    /** 用户明确要求展开实现或完整调查时，不使用业务简报模板。 */
    public boolean prefersDetailedAnswer() {
        String text = responsePreferenceText();
        return containsAny(text,
                "详细说", "详细讲", "详细说明", "详细分析", "展开讲", "展开说", "展开说明",
                "具体实现", "实现逻辑", "代码逻辑", "源码逻辑", "调用链", "完整链路", "全链路",
                "完整流程", "全流程", "查到底", "深挖", "根因分析", "代码位置", "哪个类",
                "哪个方法", "数据库表", "sql", "流程图", "架构图", "时序图", "mermaid");
    }

    /**
     * 最终回答的展示档位与内部调查深度解耦：业务用户默认只看结论；研发模式默认看实现。
     * 显式“简洁”在所有模式下优先，显式“详细/调用链”可把业务模式升级为详细回答。
     */
    public boolean usesBriefAnswer() {
        if (prefersConciseAnswer()) return true;
        String normalizedMode = mode == null || mode.isBlank()
                ? "user" : mode.trim().toLowerCase(Locale.ROOT);
        return "user".equals(normalizedMode) && !prefersDetailedAnswer();
    }

    /**
     * 缓存按回答档位和模板版本隔离，防止新版业务简报命中旧版长报告。
     * 值控制在 answer_cache.mode 的 VARCHAR(16) 范围内。
     */
    public String cacheVariant(String effectiveMode) {
        String normalizedMode = effectiveMode == null ? "user" : effectiveMode.trim().toLowerCase(Locale.ROOT);
        String prefix = switch (normalizedMode) {
            case "dev" -> "d";
            case "analyze" -> "a";
            default -> "u";
        };
        return prefix + (usesBriefAnswer() ? ":b:v2" : ":d:v2");
    }

    /** 显式输出偏好会绕过答案缓存，避免复用不符合本轮格式要求的旧答案。 */
    public boolean hasExplicitResponsePreference() {
        if (flowchart != null) return true;
        String text = responsePreferenceText();
        return prefersConciseAnswer() || containsAny(text,
                "不要画图", "不用画图", "别画图", "无需画图", "不需要画图",
                "不要画流程图", "不用画流程图", "无需画流程图", "不需要画流程图",
                "不要流程图", "不用流程图", "无流程图", "不要mermaid", "不用mermaid",
                "画个图", "画一张图", "画流程图", "流程图", "架构图", "时序图", "mermaid", "diagram");
    }

    /**
     * 精确配置定位：用户已经给出项目和接口，并且只想知道开关/配置落点。
     *
     * <p>这种问题的可信终点是“配置定义 + 读取点 + 分支作用”，不是数据库/MQ 等数据终点。
     * 显式要求深挖、查到底时仍交给状态图，不走轻量路径。</p>
     */
    public boolean isTargetedConfigLookup() {
        String text = responsePreferenceText();
        boolean hasProject = specifiedProjects != null && !specifiedProjects.isEmpty();
        boolean hasConcreteTarget = (apiPath != null && !apiPath.isBlank())
                || API_PATH_IN_TEXT.matcher(question == null ? "" : question).find();
        boolean asksConfig = containsAny(text, "配置", "开关", "切换", "switch", "toggle", "灰度");
        boolean asksDeepTrace = containsAny(text, "查到底", "深挖", "完整链路", "全链路", "全流程", "根因");
        return hasProject && hasConcreteTarget && asksConfig && !asksDeepTrace;
    }

    /**
     * 用户直接给出接口路径并询问实现逻辑时，应进入带程序化闭环门禁的 trace 执行器。
     * “不用画流程图”只是输出偏好，不能被其中的“流程图”三个字误识别成分析意图。
     */
    public boolean isInterfaceLogicQuestion() {
        if (detectedApiPath() == null) return false;
        String text = responsePreferenceText()
                .replace("不用画流程图", "")
                .replace("不要画流程图", "")
                .replace("不需要画流程图", "")
                .replace("无需画流程图", "")
                .replace("流程图", "");
        return containsAny(text, "逻辑是什么", "什么逻辑", "实现逻辑", "怎么实现", "如何实现",
                "调用链", "怎么走", "如何处理", "怎么处理", "做什么", "干什么", "实现原理");
    }

    /**
     * 精确网关路由查询的可信终点是“命中的 route/location + upstream”，不需要继续追到业务数据库。
     * 用户明确要求查到底或完整业务链路时仍进入 trace。
     */
    public boolean isTargetedGatewayLookup() {
        if (detectedApiPath() == null) return false;
        String text = responsePreferenceText();
        boolean asksGateway = containsAny(text, "apisix", "网关", "路由", "转发", "转过",
                "转到", "upstream", "gateway", "nginx");
        boolean asksDeepTrace = containsAny(text, "查到底", "深挖", "完整链路", "全链路", "全流程", "根因");
        return asksGateway && !asksDeepTrace;
    }

    /** 请求字段优先，否则从用户原话提取第一个完整接口路径。 */
    public String detectedApiPath() {
        if (apiPath != null && !apiPath.isBlank()) return apiPath.trim();
        Matcher matcher = API_PATH_IN_TEXT.matcher(question == null ? "" : question);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * 输出偏好的确定性兜底。Prompt 负责引导格式，这里负责保证显式“不要画图”一定生效。
     */
    public String enforceOutputPreferences(String answer) {
        if (answer == null || useFlowchart()) return answer;
        return MERMAID_BLOCK.matcher(answer).replaceAll("")
                .replaceAll("(?:\\r?\\n){3,}", "\n\n")
                .trim();
    }

    private String responsePreferenceText() {
        return ((question == null ? "" : question) + " " + (intent == null ? "" : intent))
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }
}
