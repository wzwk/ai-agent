package dev.example.productai.agent.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceStuckDetectorTest {

    private final TraceStuckDetector detector = new TraceStuckDetector();

    @Test
    void rejectsExactDuplicateToolCall() {
        TraceState state = TraceState.start("s1", "q", "user", null);

        assertThat(detector.registerTool(state, "search_code|{a:1}")).isTrue();
        assertThat(detector.registerTool(state, "search_code|{a:1}")).isFalse();
        assertThat(detector.registerTool(state, "search_code|{a:2}")).isTrue();
    }

    @Test
    void detectsTwoConsecutiveRoundsWithoutNewFacts() {
        TraceState state = TraceState.start("s1", "q", "user", null);
        int score = state.progressScore();

        assertThat(detector.registerRoundProgress(state, score)).isFalse();
        assertThat(detector.isStuck(state)).isFalse();
        assertThat(detector.registerRoundProgress(state, score)).isFalse();
        assertThat(detector.isStuck(state)).isTrue();

        state.getReadFiles().add("p/A.java");
        assertThat(detector.registerRoundProgress(state, score)).isTrue();
        assertThat(detector.isStuck(state)).isFalse();
    }
}
