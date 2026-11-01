package dev.example.productai.rag;

import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.RagProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.model.ProjectInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * 索引器：文件级增量（SHA-256 比对，变化文件整体重建切片）。
 * chunk 级增量（content_hash 复用旧向量）是后续优化项，功能上文件级重建正确。
 *
 * @author wengzewei
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagIndexer {

    private static final int MAX_FAILURE_DETAILS = 100;

    private final JdbcTemplate jdbc;
    private final RagProperties ragProps;
    private final SearchProperties searchProps;
    private final ProjectsProperties projectsProps;
    private final ProjectsConfigLoader loader;
    private final Chunker chunker;
    private final EmbeddingClient embedding;
    private final RagIndexTransactionService transactionService;

    public record IndexFailure(String projectKey, String filePath, String stage, String message) {}

    public record IndexResult(int scanned, int indexed, int skippedUnchanged, int failed, int chunks,
                              List<IndexFailure> failures) {}

    public record IndexProgress(String currentProject, int completedProjects, int totalProjects,
                                int scanned, int indexed, int skippedUnchanged, int failed, int chunks,
                                List<IndexFailure> failures) {}

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(IndexProgress progress);
    }

    /** 对指定项目做增量索引；保留该入口供内部调用和兼容测试使用。 */
    public IndexResult indexProjects(List<String> projectKeys) {
        return indexProjects(projectKeys, progress -> {});
    }

    /** 对指定项目做增量索引，并持续报告任务进度。 */
    public IndexResult indexProjects(List<String> projectKeys, ProgressListener progressListener) {
        int scanned = 0, indexed = 0, skipped = 0, failed = 0, chunks = 0;
        List<IndexFailure> failures = new ArrayList<>();
        int completedProjects = 0;
        for (String key : projectKeys) {
            ProjectInfo p = loader.findByName(key);
            if (p == null) {
                completedProjects++;
                progressListener.onProgress(new IndexProgress(key, completedProjects, projectKeys.size(),
                        scanned, indexed, skipped, failed, chunks, List.copyOf(failures)));
                continue;
            }
            Path root = Paths.get(p.resolveAbsolutePath(projectsProps.getCodeBase())).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                completedProjects++;
                progressListener.onProgress(new IndexProgress(key, completedProjects, projectKeys.size(),
                        scanned, indexed, skipped, failed, chunks, List.copyOf(failures)));
                continue;
            }

            Map<String, String> existing = new HashMap<>();
            // 只把完整提交的文档视为未变化；旧版本曾留下的 indexing 行会在本次自动修复。
            jdbc.query("SELECT file_path, file_hash FROM rag_documents WHERE project_key = ? AND status = 'indexed'",
                    rs -> {
                        existing.put(rs.getString(1), rs.getString(2));
                    }, key);

            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(RagIndexer::isIndexableSource)
                        .filter(f -> !isExcluded(f))
                        .filter(f -> f.toFile().length() <= ragProps.getIndex().getMaxFileBytes())
                        .forEach(files::add);
            } catch (Exception e) {
                log.warn("扫描项目失败 {}: {}", key, e.getMessage());
                failed++;
                addFailure(failures, key, root.toString(), "scan", e);
                completedProjects++;
                progressListener.onProgress(new IndexProgress(key, completedProjects, projectKeys.size(),
                        scanned, indexed, skipped, failed, chunks, List.copyOf(failures)));
                continue;
            }

            Set<String> seenPaths = new HashSet<>();
            for (Path f : files) {
                scanned++;
                String rel = root.relativize(f).toString().replace('\\', '/');
                String fullPath = key + "/" + rel;
                seenPaths.add(fullPath);
                try {
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    String hash = sha256(content);
                    if (hash.equals(existing.get(fullPath))) {
                        skipped++;
                        continue;
                    }
                    List<String> lines = content.lines().toList();
                    List<Chunker.Chunk> cs = chunker.chunk(fullPath, Chunker.classNameOf(f.getFileName().toString()), lines);
                    if (cs.isEmpty()) {
                        skipped++;
                        continue;
                    }
                    // embedding 是最慢且最易失败的步骤，先在事务外全部完成，避免占用数据库连接。
                    List<RagIndexTransactionService.IndexedChunk> prepared = new ArrayList<>(cs.size());
                    for (int from = 0; from < cs.size(); from += ragProps.getEmbedding().getBatchSize()) {
                        List<Chunker.Chunk> batch = cs.subList(from, Math.min(cs.size(), from + ragProps.getEmbedding().getBatchSize()));
                        List<float[]> vectors = embedding.embed(batch.stream().map(Chunker.Chunk::content).toList());
                        for (int j = 0; j < batch.size(); j++) {
                            Chunker.Chunk c = batch.get(j);
                            prepared.add(new RagIndexTransactionService.IndexedChunk(from + j, c, vectors.get(j)));
                        }
                    }
                    // 一个文件的元数据、旧切片删除和新切片批量插入在同一事务内原子提交。
                    transactionService.replaceFile(key, fullPath, f.getFileName().toString(), hash,
                            f.toFile().length(), lines.size(), prepared);
                    indexed++;
                    chunks += cs.size();
                } catch (Exception e) {
                    failed++;
                    addFailure(failures, key, fullPath, "index", e);
                    log.warn("索引文件失败 {}: {}", fullPath, e.getMessage());
                } finally {
                    progressListener.onProgress(new IndexProgress(key, completedProjects, projectKeys.size(),
                            scanned, indexed, skipped, failed, chunks, List.copyOf(failures)));
                }
            }
            // 磁盘已删的文件：清切片 + 标记
            for (String path : existing.keySet()) {
                if (!seenPaths.contains(path)) {
                    transactionService.markDeleted(key, path);
                }
            }
            completedProjects++;
            progressListener.onProgress(new IndexProgress(key, completedProjects, projectKeys.size(),
                    scanned, indexed, skipped, failed, chunks, List.copyOf(failures)));
            log.info("RAG 索引完成 {}: scanned={}, indexed={}, skipped={}, failed={}, chunks={}",
                    key, scanned, indexed, skipped, failed, chunks);
        }
        return new IndexResult(scanned, indexed, skipped, failed, chunks, List.copyOf(failures));
    }

    /**
     * 当前向量库收录 Java 业务源码和 APISIX 自定义 Lua 插件。
     * YAML/XML 等配置仍由实时关键词检索负责，避免扩大向量重建规模。
     */
    static boolean isIndexableSource(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".lua");
    }

    private boolean isExcluded(Path f) {
        for (Path part : f) {
            if (searchProps.getExcludeDirs().contains(part.toString())) return true;
        }
        return false;
    }

    private static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void addFailure(List<IndexFailure> failures, String projectKey, String filePath,
                                   String stage, Exception error) {
        if (failures.size() >= MAX_FAILURE_DETAILS) return;
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.isBlank()) message = root.getClass().getSimpleName();
        failures.add(new IndexFailure(projectKey, filePath, stage, message));
    }
}
