package dev.example.productai.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 聊天响应
 
 * @author wengzewei*/
@Data
@Builder
public class ChatResponse {
    /** AI 的回答 */
    private String answer;

    /** 本次回答所属会话 ID（多轮对话时前端需带回） */
    private String sessionId;

    /** 本次回答使用的模式：user / dev */
    private String mode;

    /** 命中的项目名列表 */
    private List<String> matchedProjects;

    /** 命中的 feature 列表（来自 sample-album-bff 等 BFF） */
    private List<String> matchedFeatures;

    /** 引用的源代码文件列表（相对 code-base 的短路径） */
    private List<String> sourceFiles;

    /** 结构化引用：项目 + 相对路径 + 命中行号 + 命中关键词 */
    private List<SourceRef> sources;

    /** 服务级调用链（由 projects.yml 的 rpc_to/called_by 静态关系推导，非 LLM 生成） */
    private ServiceChain serviceChain;

    /** AI 的置信度：high/medium/low */
    private String confidence;

    /** 是否需要人工介入（低置信度时为 true） */
    private boolean needHuman;

    /** true = 共享免费额度已用完，前端应引导访客配置自己的 API Key */
    private Boolean needKey;

    /** 本会话剩余的共享免费提问次数（自带 Key 时为 null） */
    private Integer freeRemaining;

    /** 处理耗时（毫秒） */
    private long elapsedMs;

    /** 本次回答使用的引擎：workflow（固定管线）/ agent（智能体循环）/ cache（语义缓存命中） */
    private String engine;

    /** agent 模式的探索轨迹（步骤时间线），workflow 模式为空 */
    private List<Step> steps;

    /** agent 模式实际使用的决策轮数 */
    private Integer stepsUsed;

    /** 探索是否闭环：complete / partial；非 agent 引擎可为空。 */
    private String completionStatus;

    /** completionStatus=partial 时的机器可读说明。 */
    private String incompleteReason;

    /** 状态图引擎的调查 ID；legacy/workflow 为空。 */
    private String traceId;

    /** 状态图仍待验证的调用边数量。 */
    private Integer pendingEdges;

    /** 状态图已确认的数据终点数量。 */
    private Integer terminalCount;

    /** engine=cache 时：该历史答案累计被命中次数 */
    private Integer cacheHits;

    /** 本次请求各阶段的性能拆分，便于定位慢在决策、工具还是最终成文。 */
    private Performance performance;

    @Data
    @Builder
    public static class Performance {
        /** faq_exact / agent / trace / cache / workflow / cancelled / error */
        private String route;
        private long totalMs;
        private long decisionMs;
        private long toolMs;
        private long composeMs;
        private int decisionCalls;
        private int toolCalls;
    }

    /**
     * 智能体探索轨迹的一步
     */
    @Data
    @Builder
    public static class Step {
        /** 第几轮决策（1 起） */
        private int no;
        /** tool_call / tool_result / force_answer */
        private String phase;
        /** 工具名 */
        private String tool;
        /** 工具入参 JSON */
        private String args;
        /** 结果摘要（给人看的时间线） */
        private String summary;
        /** 该步耗时（毫秒） */
        private long elapsedMs;
        /** ok / error */
        private String status;
    }

    /**
     * 单条代码引用
     */
    @Data
    @Builder
    public static class SourceRef {
        /** 所属项目名 */
        private String project;
        /** 相对 code-base 的文件路径（正斜杠） */
        private String file;
        /** 关键词命中的行号（1-based，采样，最多 30 条） */
        private List<Integer> lines;
        /** 主命中关键词 */
        private String keyword;
        /** 是否入口类（含路由注解的 Controller） */
        private boolean entry;
    }

    /**
     * 服务级调用链：本次回答涉及的项目 + 项目间调用关系
     */
    @Data
    @Builder
    public static class ServiceChain {
        private List<ServiceNode> nodes;
        private List<ServiceEdge> edges;
    }

    @Data
    @Builder
    public static class ServiceNode {
        /** 项目名 */
        private String name;
        /** 项目描述 */
        private String description;
        /** 调用链入口（没有上游节点的项目，一般是 BFF 层） */
        private boolean entryPoint;
    }

    @Data
    @Builder
    public static class ServiceEdge {
        /** 调用方项目名 */
        private String from;
        /** 被调方项目名 */
        private String to;
        /** 关系类型：rpc */
        private String type;
    }
}
