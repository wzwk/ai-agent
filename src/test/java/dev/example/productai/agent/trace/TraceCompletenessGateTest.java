package dev.example.productai.agent.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCompletenessGateTest {

    private final TraceCompletenessGate gate = new TraceCompletenessGate();

    @Test
    void completesOnlyAfterFinishWithTerminalAndNoPendingOrBlocker() {
        TraceState state = TraceState.start("s1", "查完整链路", "user", null);
        state.setFinishRequested(true);
        state.putEdge(TraceEdge.builder().fromSymbol("Controller").toSymbol("Repository")
                .kind("repository").status("resolved").evidenceFile("p/A.java").evidenceLine(10).build());
        state.putTerminal(TraceTerminal.builder().symbol("MongoTemplate").type("mongo")
                .evidenceFile("p/A.java").evidenceLine(20).build());

        assertThat(gate.evaluate(state).complete()).isTrue();

        state.putEdge(TraceEdge.builder().fromSymbol("Service").toSymbol("FeignClient")
                .kind("feign").status("pending").build());
        TraceCompletenessGate.Result pending = gate.evaluate(state);
        assertThat(pending.complete()).isFalse();
        assertThat(pending.summary()).contains("待验证调用边");
    }

    @Test
    void explicitBlockerRemainsPartialInsteadOfPretendingComplete() {
        TraceState state = TraceState.start("s1", "查完整链路", "user", null);
        state.setFinishRequested(true);
        state.putTerminal(TraceTerminal.builder().symbol("index-products").type("es")
                .evidenceFile("p/Search.java").build());
        state.getUnresolvedReasons().add("下游项目未下载");

        assertThat(gate.evaluate(state).complete()).isFalse();
        assertThat(gate.evaluate(state).summary()).contains("明确阻断项");
    }
}
