package dev.example.productai.agent.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceStateTest {

    @Test
    void resolvedDuplicateEndpointWinsOverPendingWithDifferentKind() {
        TraceState state = TraceState.start("s", "q", "dev", null);
        state.putEdge(TraceEdge.builder().fromSymbol("GameStageController.finishGameStage")
                .toSymbol("RocketMQ").kind("mq").status("pending").build());
        state.putEdge(TraceEdge.builder().fromSymbol("GameStageController.finishGameStage")
                .toSymbol("RocketMQ").kind("other").status("resolved")
                .evidenceFile("UserStageFinishProduceService.java").build());

        assertThat(state.getEdges()).hasSize(1);
        assertThat(state.pendingEdgeCount()).isZero();
        assertThat(state.getEdges().values().iterator().next().getStatus()).isEqualTo("resolved");
    }

    @Test
    void normalizeRepairsDuplicateEdgesLoadedFromOldCheckpoint() {
        TraceState state = TraceState.start("s", "q", "dev", null);
        TraceEdge pending = TraceEdge.builder().fromSymbol("A").toSymbol("RocketMQ")
                .kind("mq").status("pending").build();
        TraceEdge resolved = TraceEdge.builder().fromSymbol("A").toSymbol("RocketMQ")
                .kind("other").status("resolved").evidenceFile("Producer.java").build();
        state.getEdges().put(pending.identity(), pending);
        state.getEdges().put(resolved.identity(), resolved);

        state.normalize();

        assertThat(state.getEdges()).hasSize(1);
        assertThat(state.pendingEdgeCount()).isZero();
    }
}
