package dev.example.productai.search;

import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.model.ProjectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeSearcherTest {

    @TempDir
    Path tempDir;

    private CodeSearcher searcher;

    @AfterEach
    void tearDown() {
        if (searcher != null) searcher.close();
    }

    @Test
    void reusesSnapshotAndInvalidatesWhenFileChanges() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("demo/src"));
        Path source = projectDir.resolve("DemoController.java");
        Files.writeString(source, "class DemoController {\n  void oldSymbol() {}\n}\n", StandardCharsets.UTF_8);
        long originalMtime = Files.getLastModifiedTime(source).toMillis();

        searcher = newSearcher(2, 100, 16);
        ProjectInfo project = ProjectInfo.builder().name("demo").path("demo").build();

        List<SearchResult> first = searcher.search(List.of(project), List.of("oldSymbol"));
        assertEquals(1, first.size());
        assertEquals(List.of(2), first.get(0).getHitLines());
        long missesAfterFirst = searcher.cacheStats().misses();
        assertTrue(missesAfterFirst >= 1);

        List<SearchResult> second = searcher.search(List.of(project), List.of("OLDSYMBOL"));
        assertEquals(1, second.size(), "普通关键词应保持忽略大小写");
        assertTrue(searcher.cacheStats().hits() >= 1, "第二次搜索应命中文件快照缓存");

        Files.writeString(source, "class DemoController {\n  void newSymbol() {}\n}\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(source, FileTime.fromMillis(originalMtime + 2_000));

        assertTrue(searcher.search(List.of(project), List.of("oldSymbol")).isEmpty());
        List<SearchResult> changed = searcher.search(List.of(project), List.of("newSymbol"));
        assertEquals(1, changed.size(), "size/mtime 变化后必须读取新内容");
        assertTrue(searcher.cacheStats().misses() > missesAfterFirst);
    }

    @Test
    void searchesProjectsConcurrentlyWithoutChangingResultOrderRules() throws Exception {
        write("one", "OneController.java", "@GetMapping(\"/api/detail\")\nclass OneController {}\n");
        write("two", "TwoService.java", "class TwoService { void detail() {} }\n");
        searcher = newSearcher(2, 100, 16);

        ProjectInfo one = ProjectInfo.builder().name("one").path("one").build();
        ProjectInfo two = ProjectInfo.builder().name("two").path("two").build();
        List<SearchResult> hits = searcher.search(List.of(one, two), List.of("/api/detail"));

        assertFalse(hits.isEmpty());
        assertEquals("one", hits.get(0).getProjectName());
        assertTrue(hits.get(0).isPathHit());
    }

    @Test
    void boundsContentCacheByEntryCount() throws Exception {
        for (int i = 0; i < 8; i++) {
            write("demo", "File" + i + ".java", "class File" + i + " { String marker = \"needle\"; }\n");
        }
        searcher = newSearcher(2, 3, 16);
        ProjectInfo project = ProjectInfo.builder().name("demo").path("demo").build();

        assertEquals(3, searcher.search(List.of(project), List.of("needle")).size(),
                "每项目结果仍受 topN 限制");
        assertTrue(searcher.cacheStats().entries() <= 3, "LRU 条目数必须受配置约束");
    }

    @Test
    void cachedReadLinesMatchesReadAllLinesForEmptyAndTrailingNewlineFiles() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("demo/src"));
        Path empty = projectDir.resolve("Empty.java");
        Path trailingNewline = projectDir.resolve("Trailing.java");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        Files.writeString(trailingNewline, "line1\nline2\n", StandardCharsets.UTF_8);
        searcher = newSearcher(1, 10, 16);

        assertEquals(Files.readAllLines(empty, StandardCharsets.UTF_8), searcher.readLines(empty));
        assertEquals(Files.readAllLines(trailingNewline, StandardCharsets.UTF_8),
                searcher.readLines(trailingNewline));
    }

    @Test
    void searchesApisixLuaPlugins() throws Exception {
        write("gateway", "header-token-interceptor.lua",
                "local plugin_name = 'header-token-interceptor'\nfunction _M.access(conf, ctx) end\n");
        searcher = newSearcher(1, 10, 16);
        ProjectInfo gateway = ProjectInfo.builder().name("gateway").path("gateway").build();

        List<SearchResult> hits = searcher.search(List.of(gateway), List.of("header-token-interceptor"));

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).getFilePath().endsWith("header-token-interceptor.lua"));
    }

    @Test
    void searchesApisixJsonAndNginxRouteArtifacts() throws Exception {
        write("sample-apisix-gateway", "decode.json",
                "{\"/api/v1/items/batch/delete\": {\"type\": 3}}\n");
        write("sample-apisix-gateway", "vhostlocation.conf",
                "location ~ ^/api { set $backend 'upstream-backend'; }\n");
        searcher = newSearcher(1, 20, 16);
        ProjectInfo gateway = ProjectInfo.builder()
                .name("sample-apisix-gateway").path("sample-apisix-gateway").build();

        List<SearchResult> hits = searcher.search(List.of(gateway),
                List.of("/api/v1/items/batch/delete"));

        assertTrue(hits.stream().anyMatch(h -> h.getFilePath().endsWith("decode.json") && h.isPathHit()));
        assertTrue(hits.stream().anyMatch(h -> h.getFilePath().endsWith("vhostlocation.conf")),
                "完整路径应派生 /api 顶层前缀并命中网关转发规则");
    }

    private CodeSearcher newSearcher(int parallelism, int maxEntries, int maxMb) {
        SearchProperties search = new SearchProperties();
        search.setParallelism(parallelism);
        search.setTopNPerProject(3);
        search.setFileListCacheTtlMs(60_000);
        search.setContentCacheMaxEntries(maxEntries);
        search.setContentCacheMaxMb(maxMb);
        ProjectsProperties projects = new ProjectsProperties();
        projects.setCodeBase(tempDir.toString());
        return new CodeSearcher(search, projects);
    }

    private void write(String project, String file, String content) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(project).resolve("src"));
        Files.writeString(dir.resolve(file), content, StandardCharsets.UTF_8);
    }
}
