package dev.example.productai.agent;

import dev.example.productai.llm.LlmRuntime;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次问答的完整上下文参数。
 * 普通聊天只填 question/mode/sessionId/rt；
 * 接口分析模块额外指定 projects/apiPath/intent/flowchart，可跳过 LLM 路由。
 
 * @author wengzewei*/
@Data
@Builder
public class AskContext {
    private String question;
    private String mode;
    private String sessionId;
    private LlmRuntime rt;

    /** 指定检索的项目名列表；非空时跳过 LLM 路由，直接在这些项目内检索 */
    private List<String> specifiedProjects;

    /** 接口路径，如 /api/v3/commodity/multiPriceSkuVersionSave；作为最高优先级检索关键词 */
    private String apiPath;

    /** 用户自定义分析目标：梳理逻辑 / 排查报错 / 评估改动影响等 */
    private String intent;

    /** dev 模式是否生成 mermaid 流程图（默认 true；流程图较耗 token 可关闭） */
    private Boolean flowchart;

    /** 引擎指定：null/空 = 跟随配置；workflow/agent/legacy/trace 可用于评测和一键回退。 */
    private String engine;

    public boolean useFlowchart() {
        return flowchart == null || flowchart;
    }
}
