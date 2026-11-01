package dev.example.productai.agent;

import dev.example.productai.llm.LlmCancellation;
import dev.example.productai.llm.LlmCancelledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionGuardTest {

    private final ToolExecutionGuard guard = new ToolExecutionGuard();

    @AfterEach
    void cleanup() {
        LlmCancellation.unbind();
        guard.shutdown();
    }

    @Test
    void returnsFastToolResult() {
        assertThat(guard.execute("fast", 500, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void interruptsToolAtWallClockTimeout() {
        assertThatThrownBy(() -> guard.execute("slow", 30, () -> {
            Thread.sleep(5_000);
            return "late";
        })).isInstanceOf(ToolExecutionGuard.ToolTimeoutException.class)
                .hasMessageContaining("slow");
    }

    @Test
    void propagatesRequestCancellationIntoWorker() {
        AtomicBoolean token = new AtomicBoolean(true);
        LlmCancellation.bind(token);

        assertThatThrownBy(() -> guard.execute("cancelled", 500, () -> "never"))
                .isInstanceOf(LlmCancelledException.class);
    }
}
