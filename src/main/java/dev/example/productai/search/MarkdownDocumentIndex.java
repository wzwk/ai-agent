package dev.example.productai.search;

import dev.example.productai.config.ProjectsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Markdown 文档的进程内快照。目录只做短 TTL 元数据检查，正文按 path + mtime 复用，
 * 避免每个 FAQ/文档问题都重新读取全部文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkdownDocumentIndex {

    private static final long CHECK_TTL_MS = 10_000L;

    private final ProjectsProperties projectsProperties;
    private volatile Snapshot snapshot = new Snapshot(0L, Map.of());

    public List<Document> documents() {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (now - current.checkedAt() < CHECK_TTL_MS) {
            return List.copyOf(current.documents().values());
        }
        return refresh(now);
    }

    private synchronized List<Document> refresh(long now) {
        Snapshot current = snapshot;
        if (now - current.checkedAt() < CHECK_TTL_MS) {
            return List.copyOf(current.documents().values());
        }
        Path docsRoot = Paths.get(projectsProperties.getCodeBase(), "docs").toAbsolutePath().normalize();
        if (!Files.isDirectory(docsRoot)) {
            snapshot = new Snapshot(now, Map.of());
            return List.of();
        }

        Map<Path, Document> next = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(docsRoot)) {
            for (Path raw : walk.filter(path -> Files.isRegularFile(path)
                            && path.toString().toLowerCase().endsWith(".md"))
                    .sorted().toList()) {
                Path path = raw.toAbsolutePath().normalize();
                long modified = Files.getLastModifiedTime(path).toMillis();
                Document cached = current.documents().get(path);
                if (cached != null && cached.modifiedAt() == modified) {
                    next.put(path, cached);
                    continue;
                }
                List<String> lines = List.copyOf(Files.readAllLines(path));
                String relative = "docs/" + docsRoot.relativize(path).toString().replace('\\', '/');
                next.put(path, new Document(path, relative, modified, lines));
            }
            snapshot = new Snapshot(now, Collections.unmodifiableMap(new LinkedHashMap<>(next)));
            log.info("Markdown 文档索引刷新完成 docs={}, reused={}", next.size(),
                    next.values().stream().filter(doc -> current.documents().get(doc.path()) == doc).count());
        } catch (Exception e) {
            // 刷新失败时保留上一份可用快照，不让单个临时 IO 故障拖垮问答。
            snapshot = new Snapshot(now, current.documents());
            log.warn("Markdown 文档索引刷新失败，继续使用旧快照: {}", e.getMessage());
        }
        return new ArrayList<>(snapshot.documents().values());
    }

    public record Document(Path path, String relativePath, long modifiedAt, List<String> lines) {
    }

    private record Snapshot(long checkedAt, Map<Path, Document> documents) {
    }
}
