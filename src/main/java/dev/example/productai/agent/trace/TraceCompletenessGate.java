package dev.example.productai.agent.trace;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 完成状态由程序判定，不由模型“感觉已经够了”决定。 */
@Component
public class TraceCompletenessGate {

    public Result evaluate(TraceState state) {
        List<String> reasons = new ArrayList<>();
        if (!state.isFinishRequested()) reasons.add("模型尚未提交闭环检查");
        int pending = state.pendingEdgeCount();
        if (pending > 0) reasons.add("仍有 " + pending + " 条待验证调用边");
        if (state.getTerminals().isEmpty()) reasons.add("尚未确认数据库/ES/Redis/MQ/外部系统等终点");
        if (!state.getUnresolvedReasons().isEmpty()) {
            reasons.add("仍有 " + state.getUnresolvedReasons().size() + " 个明确阻断项");
        }
        return new Result(reasons.isEmpty(), List.copyOf(reasons));
    }

    public record Result(boolean complete, List<String> reasons) {
        public String summary() {
            return complete ? "所有登记分支均已闭环" : String.join("；", reasons);
        }
    }
}
