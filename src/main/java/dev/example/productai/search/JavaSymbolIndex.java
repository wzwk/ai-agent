package dev.example.productai.search;

import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.model.ProjectInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 轻量级 Java 符号索引：按文件名把类/接口精确定位到项目和文件。
 * 它不替代 grep，只负责把“全库扫描”降级为“直接定位后精读”。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JavaSymbolIndex {

    private final ProjectsConfigLoader loader;
    private final ProjectsProperties projectsProperties;
    private final SearchProperties searchProperties;

    private volatile Snapshot snapshot = new Snapshot(0, Map.of(), 0);

    public List<Hit> find(String rawSymbol, List<String> projectKeys) {
        String symbol = simpleName(rawSymbol);
        if (symbol.isBlank()) return List.of();
        Snapshot current = ensureFresh();
        Set<String> scope = projectKeys == null ? Set.of() : Set.copyOf(projectKeys);
        return current.bySimpleName().getOrDefault(symbol.toLowerCase(Locale.ROOT), List.of()).stream()
                .filter(hit -> scope.isEmpty() || scope.contains(hit.project()))
                .sorted(Comparator.comparing(Hit::project).thenComparing(Hit::file))
                .limit(20)
                .toList();
    }

    public Stats stats() {
        Snapshot current = ensureFresh();
        return new Stats(current.bySimpleName().size(), current.fileCount(), current.loadedAtMs());
    }

    public synchronized void invalidate() {
        snapshot = new Snapshot(0, Map.of(), 0);
    }

    private Snapshot ensureFresh() {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot;
        if (current.loadedAtMs() > 0
                && now - current.loadedAtMs() <= Math.max(1_000, searchProperties.getSymbolIndexTtlMs())) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current.loadedAtMs() > 0
                    && now - current.loadedAtMs() <= Math.max(1_000, searchProperties.getSymbolIndexTtlMs())) {
                return current;
            }
            snapshot = build(now);
            return snapshot;
        }
    }

    private Snapshot build(long loadedAt) {
        long started = System.currentTimeMillis();
        Map<String, List<Hit>> mutable = new LinkedHashMap<>();
        int[] files = {0};
        Path codeBase = Paths.get(projectsProperties.getCodeBase()).toAbsolutePath().normalize();
        for (ProjectInfo project : loader.getProjects()) {
            Path root = Paths.get(project.resolveAbsolutePath(projectsProperties.getCodeBase()))
                    .toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) continue;
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        return searchProperties.getExcludeDirs().contains(name)
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".java") || name.length() <= 5) return FileVisitResult.CONTINUE;
                        files[0]++;
                        String simple = name.substring(0, name.length() - 5);
                        String relative = codeBase.relativize(file.toAbsolutePath().normalize())
                                .toString().replace('\\', '/');
                        mutable.computeIfAbsent(simple.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                                .add(new Hit(simple, project.getName(), relative));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.debug("构建符号索引时跳过项目 {}: {}", project.getName(), e.getMessage());
            }
        }
        Map<String, List<Hit>> immutable = new LinkedHashMap<>();
        mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        log.info("Java 符号索引刷新完成 symbols={}, files={}, elapsedMs={}",
                immutable.size(), files[0], System.currentTimeMillis() - started);
        return new Snapshot(loadedAt, Map.copyOf(immutable), files[0]);
    }

    private String simpleName(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        int hash = value.lastIndexOf('#');
        if (hash >= 0) value = value.substring(0, hash);
        int dot = value.lastIndexOf('.');
        if (dot >= 0) value = value.substring(dot + 1);
        return value.replaceAll("[^A-Za-z0-9_$]", "");
    }

    public record Hit(String symbol, String project, String file) {}
    public record Stats(int symbols, int files, long loadedAtMs) {}
    private record Snapshot(long loadedAtMs, Map<String, List<Hit>> bySimpleName, int fileCount) {}
}
