package dev.example.productai.controller;

import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.rag.EmbeddingClient;
import dev.example.productai.rag.RagIndexJobService;
import dev.example.productai.rag.VectorSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RagAdminControllerTest {

    @Test
    void reindexReturnsImmediatelyWithJobAndExposesStatusEndpoint() throws Exception {
        RagIndexJobService jobs = mock(RagIndexJobService.class);
        VectorSearchService searcher = mock(VectorSearchService.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        ProjectsConfigLoader loader = mock(ProjectsConfigLoader.class);
        RagIndexJobService.JobSnapshot queued = new RagIndexJobService.JobSnapshot(
                "job-1", RagIndexJobService.State.QUEUED, List.of("demo"), null,
                0, 1, 0, 0, 0, 0, 0, List.of(), false, null,
                Instant.parse("2026-10-14T12:00:00Z"), null, null);
        when(jobs.start(List.of("demo")))
                .thenReturn(new RagIndexJobService.StartResult(true, "RAG 索引任务已提交", queued));
        when(jobs.status()).thenReturn(queued);

        RagAdminController controller = new RagAdminController(jobs, searcher, embedding, loader);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/admin/rag/reindex").param("projects", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.state").value("QUEUED"));

        mvc.perform(get("/api/admin/rag/reindex/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.totalProjects").value(1));

        verify(jobs).start(List.of("demo"));
        verify(jobs).status();
        verifyNoInteractions(embedding, searcher, loader);
    }
}
