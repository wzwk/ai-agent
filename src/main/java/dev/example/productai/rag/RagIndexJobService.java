package dev.example.productai.rag;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** 单进程 RAG 异步任务调度器：一次只允许一个索引任务运行。 */
@Slf4j
@Service
public class RagIndexJobService {

    public enum State { IDLE, QUEUED, RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED }

    public record JobSnapshot(
            String jobId,
            State state,
            List<String> projects,
            String currentProject,
            int completedProjects,
            int totalProjects,
            int scanned,
            int indexed,
            int skippedUnchanged,
            int failed,
            int chunks,
            List<RagIndexer.IndexFailure> failures,
            boolean failureDetailsTruncated,
            String error,
            Instant submittedAt,
            Instant startedAt,
            Instant finishedAt
    ) {
        public boolean active() {
            return state == State.QUEUED || state == State.RUNNING;
        }
    }

    public record StartResult(boolean accepted, String message, JobSnapshot job) {}

    private final RagIndexer indexer;
    private final EmbeddingClient embedding;
    private final ExecutorService executor;
    private final AtomicReference<JobSnapshot> current = new AtomicReference<>(idleSnapshot());

    @Autowired
    public RagIndexJobService(RagIndexer indexer, EmbeddingClient embedding) {
        this(indexer, embedding, Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "rag-indexer");
            thread.setDaemon(true);
            return thread;
        }));
    }

    RagIndexJobService(RagIndexer indexer, EmbeddingClient embedding, ExecutorService executor) {
        this.indexer = indexer;
        this.embedding = embedding;
        this.executor = executor;
    }

    public synchronized StartResult start(List<String> requestedProjects) {
        JobSnapshot running = current.get();
        if (running.active()) {
            return new StartResult(false, "已有 RAG 索引任务正在运行", running);
        }

        List<String> projects = requestedProjects.stream().distinct().toList();
        Instant now = Instant.now();
        JobSnapshot queued = new JobSnapshot(
                UUID.randomUUID().toString(), State.QUEUED, projects, null,
                0, projects.size(), 0, 0, 0, 0, 0,
                List.of(), false,
                null, now, null, null);
        current.set(queued);
        executor.submit(() -> run(queued));
        return new StartResult(true, "RAG 索引任务已提交", queued);
    }

    public JobSnapshot status() {
        return current.get();
    }

    private void run(JobSnapshot queued) {
        Instant startedAt = Instant.now();
        current.set(copy(queued, State.RUNNING, null, 0, 0, 0, 0, 0, 0,
                List.of(), false,
                null, startedAt, null));
        try {
            if (!embedding.ping()) {
                throw new IllegalStateException("Ollama 未启动（localhost:11434 不可达），先启动再索引");
            }
            RagIndexer.IndexResult result = indexer.indexProjects(queued.projects(), progress ->
                    current.set(copy(queued, State.RUNNING, progress.currentProject(),
                            progress.completedProjects(), progress.scanned(), progress.indexed(),
                            progress.skippedUnchanged(), progress.failed(), progress.chunks(),
                            progress.failures(), progress.failed() > progress.failures().size(),
                            null, startedAt, null)));
            State terminalState = result.failed() == 0 ? State.SUCCESS : State.PARTIAL_SUCCESS;
            current.set(copy(queued, terminalState, null, queued.projects().size(),
                    result.scanned(), result.indexed(), result.skippedUnchanged(), result.failed(), result.chunks(),
                    result.failures(), result.failed() > result.failures().size(),
                    null, startedAt, Instant.now()));
            log.info("RAG 异步索引完成 jobId={}, result={}", queued.jobId(), result);
        } catch (Exception e) {
            JobSnapshot latest = current.get();
            current.set(new JobSnapshot(
                    queued.jobId(), State.FAILED, queued.projects(), latest.currentProject(),
                    latest.completedProjects(), queued.projects().size(), latest.scanned(), latest.indexed(),
                    latest.skippedUnchanged(), latest.failed(), latest.chunks(), latest.failures(),
                    latest.failureDetailsTruncated(), rootMessage(e),
                    queued.submittedAt(), startedAt, Instant.now()));
            log.error("RAG 异步索引失败 jobId={}", queued.jobId(), e);
        }
    }

    private static JobSnapshot copy(JobSnapshot base,
                                    State state,
                                    String currentProject,
                                    int completedProjects,
                                    int scanned,
                                    int indexed,
                                    int skipped,
                                    int failed,
                                    int chunks,
                                    List<RagIndexer.IndexFailure> failures,
                                    boolean failureDetailsTruncated,
                                    String error,
                                    Instant startedAt,
                                    Instant finishedAt) {
        return new JobSnapshot(base.jobId(), state, base.projects(), currentProject,
                completedProjects, base.projects().size(), scanned, indexed, skipped, failed, chunks,
                failures == null ? List.of() : List.copyOf(failures), failureDetailsTruncated,
                error, base.submittedAt(), startedAt, finishedAt);
    }

    private static JobSnapshot idleSnapshot() {
        return new JobSnapshot(null, State.IDLE, List.of(), null,
                0, 0, 0, 0, 0, 0, 0, List.of(), false, null,
                null, null, null);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
