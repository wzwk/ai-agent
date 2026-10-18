package dev.example.productai.agent.trace;

import org.springframework.stereotype.Component;

/** 重复工具调用和连续无新增事实检测。 */
@Component
public class TraceStuckDetector {

    public boolean registerTool(TraceState state, String signature) {
        return state.getExecutedToolSignatures().add(signature);
    }

    public boolean registerRoundProgress(TraceState state, int scoreBefore) {
        boolean progressed = state.progressScore() > scoreBefore;
        state.setNoProgressRounds(progressed ? 0 : state.getNoProgressRounds() + 1);
        return progressed;
    }

    public boolean isStuck(TraceState state) {
        return state.getNoProgressRounds() >= 2;
    }
}
