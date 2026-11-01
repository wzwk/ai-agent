package dev.example.productai.agent;

import dev.example.productai.llm.LlmCancellation;
import dev.example.productai.llm.LlmCancelledException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行的统一墙钟与取消边界。
 *
 * <p>工具在虚拟线程中运行；请求线程每 100ms 检查一次用户取消标记。超时或取消时会
 * 中断工作线程，避免一次文件扫描、HTTP 或数据库调用无限占住整个 Agent 请求。</p>
 */
@Slf4j
@Component
public class ToolExecutionGuard {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public <T> T execute(String toolName, long timeoutMs, Callable<T> task) {
        long effectiveTimeout = Math.max(1, timeoutMs);
        AtomicBoolean cancellation = LlmCancellation.token();
        Future<T> future = executor.submit(() -> {
            if (cancellation != null) LlmCancellation.bind(cancellation);
            try {
                return task.call();
            } finally {
                if (cancellation != null) LlmCancellation.unbind();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeout);
        try {
            while (true) {
                if (cancellation != null && cancellation.get()) {
                    future.cancel(true);
                    throw new LlmCancelledException();
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    log.warn("工具执行超时 tool={}, timeoutMs={}", toolName, effectiveTimeout);
                    throw new ToolTimeoutException(toolName, effectiveTimeout);
                }
                long waitMs = Math.min(100, Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                try {
                    return future.get(waitMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 继续检查墙钟和用户取消标记。
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmCancelledException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("工具执行失败: " + toolName, cause);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public static final class ToolTimeoutException extends RuntimeException {
        private final String toolName;
        private final long timeoutMs;

        ToolTimeoutException(String toolName, long timeoutMs) {
            super("工具 " + toolName + " 超过 " + timeoutMs + "ms 未完成");
            this.toolName = toolName;
            this.timeoutMs = timeoutMs;
        }

        public String toolName() {
            return toolName;
        }

        public long timeoutMs() {
            return timeoutMs;
        }
    }
}
