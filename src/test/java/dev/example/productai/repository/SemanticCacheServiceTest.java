package dev.example.productai.repository;

import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.model.ChatResponse;
import dev.example.productai.model.ProjectInfo;
import dev.example.productai.rag.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SemanticCacheServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void partialAgentAnswerNeverEntersCacheEvenWhenConfidenceIsHigh() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        SemanticCacheService service = new SemanticCacheService(jdbc, new ProjectsProperties(), embedding, loader(List.of()));
        ReflectionTestUtils.setField(service, "enabled", true);

        service.store("查到底", "user", ChatResponse.builder()
                .answer("只查到中间层")
                .confidence("high")
                .needHuman(false)
                .engine("agent")
                .completionStatus("partial")
                .build());

        verifyNoInteractions(jdbc, embedding);
    }

    @Test
    void agentAnswerWithoutExplicitCompletionProofNeverEntersCache() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        SemanticCacheService service = new SemanticCacheService(jdbc, new ProjectsProperties(), embedding, loader(List.of()));
        ReflectionTestUtils.setField(service, "enabled", true);

        service.store("普通问题", "user", ChatResponse.builder()
                .answer("旧格式回答")
                .confidence("high")
                .needHuman(false)
                .engine("agent")
                .build());

        verifyNoInteractions(jdbc, embedding);
    }

    @Test
    void verifiedExactTraceCacheRequiresUnchangedReferencedFiles() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProjectsProperties projects = new ProjectsProperties();
        projects.setCodeBase(tempDir.toString());
        Path source = tempDir.resolve("demo/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}");

        SemanticCacheService service = new SemanticCacheService(jdbc, projects, mock(EmbeddingClient.class), loader(List.of()));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "ttlHours", 72);
        Map<String, Object> row = Map.of(
                "id", 7L, "answer", "closed", "confidence", "high",
                "source_files", "[\"demo/A.java\"]", "steps_json", "[]",
                "engine", "trace", "steps_used", 8, "hit_count", 1,
                "created_at", Timestamp.from(Instant.now().plusSeconds(2)));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        SemanticCacheService.CacheHit hit = service.findVerifiedExact("查完整链路", "user");

        assertThat(hit).isNotNull();
        assertThat(hit.answer()).isEqualTo("closed");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void knowledgeScopeChangesWhenAProjectIsAdded() {
        ProjectInfo album = ProjectInfo.builder().name("sample-album-bff").path("sample-album-bff").priority("P0").build();
        ProjectInfo gateway = ProjectInfo.builder().name("sample-apisix-gateway")
                .path("sample-apisix-gateway").priority("P0").build();

        String before = SemanticCacheService.knowledgeScope(List.of(album));
        String after = SemanticCacheService.knowledgeScope(List.of(album, gateway));

        assertThat(after).isNotEqualTo(before);
        assertThat(after).hasSize(64);
    }

    @Test
    void knowledgeScopeChangesWhenGitHeadChanges() throws Exception {
        Path repo = tempDir.resolve("demo");
        Path refs = repo.resolve(".git/refs/heads");
        Files.createDirectories(refs);
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/preprod\n");
        Files.writeString(refs.resolve("preprod"), "1111111111111111111111111111111111111111\n");
        ProjectInfo project = ProjectInfo.builder().name("demo").path("demo").priority("P0").build();

        String before = SemanticCacheService.knowledgeScope(List.of(project), tempDir.toString());
        Files.writeString(refs.resolve("preprod"), "2222222222222222222222222222222222222222\n");
        String after = SemanticCacheService.knowledgeScope(List.of(project), tempDir.toString());

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void semanticCacheRejectsAnswerWhenReferencedSourceChanged() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        ProjectsProperties projects = new ProjectsProperties();
        projects.setCodeBase(tempDir.toString());
        Path source = tempDir.resolve("demo/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A { int changed; }");
        Instant cachedAt = Instant.now().minusSeconds(30);
        Map<String, Object> row = Map.of(
                "id", 9L, "question_raw", "A逻辑", "answer", "old", "confidence", "high",
                "source_files", "[\"demo/A.java\"]", "steps_json", "[]",
                "engine", "agent", "steps_used", 2, "hit_count", 1,
                "created_at", Timestamp.from(cachedAt));
        when(embedding.embedOne(anyString())).thenThrow(new IllegalStateException("offline"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row));

        SemanticCacheService service = new SemanticCacheService(jdbc, projects, embedding, loader(List.of()));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "ttlHours", 72);
        ReflectionTestUtils.setField(service, "threshold", 0.90d);
        ReflectionTestUtils.setField(service, "scanLimit", 30);

        assertThat(service.find("A逻辑", "user")).isNull();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private ProjectsConfigLoader loader(List<ProjectInfo> projects) {
        ProjectsConfigLoader loader = mock(ProjectsConfigLoader.class);
        when(loader.getProjects()).thenReturn(projects);
        return loader;
    }
}
