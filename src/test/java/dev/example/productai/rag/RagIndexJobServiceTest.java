package dev.example.productai.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RagIndexJobServiceTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void runsInBackgroundAndRejectsConcurrentJob() throws Exception {
        RagIndexer indexer = mock(RagIndexer.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(embedding.ping()).thenReturn(true);
        when(indexer.indexProjects(any(), any())).thenAnswer(invocation -> {
            RagIndexer.ProgressListener listener = invocation.getArgument(1);
            listener.onProgress(new RagIndexer.IndexProgress("demo", 0, 1, 3, 1, 2, 0, 4, List.of()));
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return new RagIndexer.IndexResult(3, 1, 2, 0, 4, List.of());
        });

        RagIndexJobService service = new RagIndexJobService(indexer, embedding, executor);
        RagIndexJobService.StartResult first = service.start(List.of("demo"));

        assertTrue(first.accepted());
        assertNotNull(first.job().jobId());
        assertTrue(started.await(3, TimeUnit.SECONDS));

        RagIndexJobService.StartResult duplicate = service.start(List.of("other"));
        assertFalse(duplicate.accepted());
        assertEquals(first.job().jobId(), duplicate.job().jobId());
        assertEquals(3, service.status().scanned());

        release.countDown();
        RagIndexJobService.JobSnapshot finished = awaitTerminal(service, Duration.ofSeconds(3));
        assertEquals(RagIndexJobService.State.SUCCESS, finished.state());
        assertEquals(1, finished.indexed());
        assertEquals(4, finished.chunks());
        assertNotNull(finished.finishedAt());
        verify(indexer, times(1)).indexProjects(any(), any());
    }

    @Test
    void reportsOllamaFailureWithoutStartingIndexer() throws Exception {
        RagIndexer indexer = mock(RagIndexer.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.ping()).thenReturn(false);
        RagIndexJobService service = new RagIndexJobService(indexer, embedding, executor);

        assertTrue(service.start(List.of("demo")).accepted());

        RagIndexJobService.JobSnapshot finished = awaitTerminal(service, Duration.ofSeconds(3));
        assertEquals(RagIndexJobService.State.FAILED, finished.state());
        assertTrue(finished.error().contains("Ollama"));
        verifyNoInteractions(indexer);
    }

    @Test
    void reportsPartialSuccessAndFailureDetails() throws Exception {
        RagIndexer indexer = mock(RagIndexer.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        RagIndexer.IndexFailure failure = new RagIndexer.IndexFailure(
                "demo", "demo/Broken.java", "index", "embedding timeout");
        when(embedding.ping()).thenReturn(true);
        when(indexer.indexProjects(any(), any())).thenReturn(
                new RagIndexer.IndexResult(2, 1, 0, 1, 3, List.of(failure)));
        RagIndexJobService service = new RagIndexJobService(indexer, embedding, executor);

        assertTrue(service.start(List.of("demo")).accepted());

        RagIndexJobService.JobSnapshot finished = awaitTerminal(service, Duration.ofSeconds(3));
        assertEquals(RagIndexJobService.State.PARTIAL_SUCCESS, finished.state());
        assertEquals(1, finished.failed());
        assertEquals(List.of(failure), finished.failures());
        assertFalse(finished.failureDetailsTruncated());
    }

    private static RagIndexJobService.JobSnapshot awaitTerminal(RagIndexJobService service,
                                                                 Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            RagIndexJobService.JobSnapshot snapshot = service.status();
            if (!snapshot.active()) return snapshot;
            Thread.sleep(10);
        }
        fail("索引任务未在测试超时内结束");
        return service.status();
    }
}
