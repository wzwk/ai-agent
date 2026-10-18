package dev.example.productai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 智能体模式配置。
 * 预算按"决策轮"计（一轮可含多个并行 tool_calls），不按调用次数计。
 *
 * @author wengzewei
 */
@Data
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /**
     * 执行器选择：legacy=原自由工具循环；trace=全部使用状态图；
     * hybrid=普通问答走 legacy，深度追踪/接口分析走 trace。
     */
    private String executor = "hybrid";

    /** 总开关：false 时全部请求走旧 workflow 管线（灰度兜底 + 评测对比） */
    private boolean enabled = false;

    /** 各模式步数预算；硬顶 HARD_CAP_BUDGET 不可突破 */
    private Map<String, Integer> modeBudgets = Map.of(
            "user", 6,
            "analyze", 12,
            "dev", 18
    );

    /** 用户明确要求追完整链路时使用的探索预算，不改变回答口吻。 */
    private int deepTraceBudget = 12;

    /** 深度追踪在最后一轮仍发现新线索时允许的收尾轮数。 */
    private int deepTraceGraceTurns = 2;

    /** 无需额外 LLM 分类即可识别的深度追踪表达，可通过配置覆盖。 */
    private List<String> deepTraceKeywords = List.of(
            "查到底", "查完整", "完整链路", "全链路", "继续查", "接着查", "继续探索", "继续往下查", "继续深挖",
            "追到数据源", "最终调用", "最终数据源", "不要停在接口", "一查到底", "全部查完", "一定要查完",
            "trace to the end", "full call chain", "end-to-end"
    );

    /** 单问累计 token 二道闸（触发后强制成文） */
    private int tokenBudget = 400_000;

    /** trace 探索阶段墙钟预算；到点保存 checkpoint 并成文，后续可继续。 */
    private long traceMaxExploreMs = 240_000;

    /** trace 收尾状态提交使用的最近材料上限（字符）。 */
    private int traceCommitContextBytes = 24_000;

    /** trace 最终成文使用的最近材料上限（字符）；完整状态图另行提供。 */
    private int traceFinalContextBytes = 36_000;

    /** 每轮模型检索后由程序自动批量实读的候选文件数，减少“搜索一轮、读文件一轮”的往返。 */
    private int traceAutoExpandFiles = 2;

    /** 单工具执行超时（毫秒） */
    private int toolTimeoutMs = 10_000;

    /** 工具结果累计上限（字节），超限压缩最旧的工具结果 */
    private int contextCapBytes = 100_000;

    /** 单个工具结果上限（字节） */
    private int toolResultCapBytes = 12_000;

    public int budgetFor(String mode) {
        Integer v = modeBudgets.get(mode);
        if (v == null) v = modeBudgets.get("user");
        return Math.min(v == null ? 6 : v, HARD_CAP_BUDGET);
    }

    /** 回答模式只控制口吻；探索深度由本次问题单独决定。 */
    public int budgetFor(String mode, boolean deepTrace) {
        int base = budgetFor(mode);
        return deepTrace ? Math.min(Math.max(base, deepTraceBudget), HARD_CAP_BUDGET) : base;
    }

    public int graceTurnsFor(boolean deepTrace) {
        return deepTrace ? Math.max(0, Math.min(deepTraceGraceTurns, HARD_CAP_BUDGET)) : 0;
    }

    public boolean isDeepTraceRequest(String question, String intent) {
        String text = ((question == null ? "" : question) + " " + (intent == null ? "" : intent))
                .toLowerCase(Locale.ROOT);
        return deepTraceKeywords != null && deepTraceKeywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> k.toLowerCase(Locale.ROOT))
                .anyMatch(text::contains);
    }

    /** 全局硬顶：任何配置不得突破，防失控循环 */
    public static final int HARD_CAP_BUDGET = 40;
}
