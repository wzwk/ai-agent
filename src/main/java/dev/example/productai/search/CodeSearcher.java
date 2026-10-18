package dev.example.productai.search;

import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.model.ProjectInfo;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在指定项目目录内做关键词检索（grep 风格）
 * 支持多关键词 OR 命中，命中次数排序
 
 * @author wengzewei*/
@Slf4j
@Component
public class CodeSearcher {

    private final SearchProperties searchProps;
    private final ProjectsProperties projectsProps;
    private final ExecutorService scanExecutor;

    /**
     * path -> 文件快照。LinkedHashMap(accessOrder=true) 提供有界 LRU；访问统一走 cacheLock。
     * 快照按 size + mtime 自动失效，Git pull/切分支后无需手动清缓存。
     */
    private final Object cacheLock = new Object();
    private final LinkedHashMap<Path, SourceSnapshot> contentCache =
            new LinkedHashMap<>(1024, 0.75f, true);
    private long contentCacheBytes;
    private final AtomicLong contentCacheHits = new AtomicLong();
    private final AtomicLong contentCacheMisses = new AtomicLong();

    /** 项目目录遍历短缓存；避免一次 Agent 请求内多次从根目录 walk。 */
    private final ConcurrentHashMap<Path, ProjectFileList> projectFileCache = new ConcurrentHashMap<>();

    public CodeSearcher(SearchProperties searchProps, ProjectsProperties projectsProps) {
        this.searchProps = searchProps;
        this.projectsProps = projectsProps;
        int workers = Math.max(1, searchProps.getParallelism());
        AtomicInteger seq = new AtomicInteger();
        this.scanExecutor = Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "code-search-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void close() {
        scanExecutor.shutdownNow();
    }

    /** 便于管理端/测试观察缓存，不暴露具体文件内容。 */
    public CacheStats cacheStats() {
        synchronized (cacheLock) {
            return new CacheStats(contentCache.size(), contentCacheBytes,
                    contentCacheHits.get(), contentCacheMisses.get(), projectFileCache.size());
        }
    }

    /** 显式清理只用于测试、诊断或配置变化；正常代码更新依赖 size + mtime 自动失效。 */
    public void clearCaches() {
        synchronized (cacheLock) {
            contentCache.clear();
            contentCacheBytes = 0;
        }
        projectFileCache.clear();
    }

    /** 统一的 UTF-8 文件读取入口，供 Agent 的 read_file/search_in_file 复用同一缓存。 */
    public List<String> readLines(Path file) throws IOException {
        return sourceSnapshot(file).lines();
    }

    public record CacheStats(int entries, long estimatedBytes, long hits, long misses,
                             int projectFileLists) {}

    private record ProjectFileList(long loadedAtMs, List<Path> files) {}

    private record ProjectScanResult(List<SearchResult> hits, int scannedFiles) {}

    /** Spring 路由注解，命中次数用来给 Controller 类文件加权 */
    private static final String[] ROUTE_ANNOTATIONS = {
            "@RequestMapping", "@GetMapping", "@PostMapping",
            "@PutMapping", "@DeleteMapping", "@PatchMapping"
    };

    /** 单个文件最多记录多少条命中行号（防止超大文件把结果撑爆） */
    private static final int MAX_HIT_LINES = 400;

    /** Java import 语句（含 static import） */
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * 引用发现：扫描命中文件的 import，识别它们引用了哪些"其他已配置项目"的包。
     * 用于把路由没覆盖到的下游服务（如 storage-service / search-service）自动补进检索范围。
     *
     * @param hits       首轮命中的文件（按相关性排序）
     * @param candidates 全部已配置项目（提供 packages 包根信息）
     * @param scanLimit  最多扫描多少个文件
     * @return key=被发现的项目名, value=调用方项目名集合；只包含配置了 packages 的项目
     */
    public Map<String, Set<String>> discoverReferences(List<SearchResult> hits,
                                                       List<dev.example.productai.model.ProjectInfo> candidates,
                                                       int scanLimit) {
        // 包前缀 -> 项目名；同前缀取最长者优先匹配
        List<Map.Entry<String, String>> pkgIndex = new ArrayList<>();
        for (dev.example.productai.model.ProjectInfo p : candidates) {
            if (p.getPackages() == null) continue;
            for (String pkg : p.getPackages()) {
                if (pkg != null && !pkg.isBlank()) {
                    pkgIndex.add(Map.entry(pkg.endsWith(".") ? pkg : pkg + ".", p.getName()));
                }
            }
        }
        pkgIndex.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));

        Map<String, Set<String>> discovered = new LinkedHashMap<>();
        int scanned = 0;
        for (SearchResult h : hits) {
            if (scanned >= scanLimit) break;
            scanned++;
            try {
                String content = sourceSnapshot(Paths.get(h.getFilePath())).content();
                Matcher m = IMPORT_PATTERN.matcher(content);
                Set<String> fileRefs = new LinkedHashSet<>();
                while (m.find()) {
                    String imp = m.group(1);
                    for (Map.Entry<String, String> e : pkgIndex) {
                        if (!e.getValue().equals(h.getProjectName()) && imp.startsWith(e.getKey())) {
                            fileRefs.add(e.getValue());
                            break; // 最长前缀已命中，跳出包索引
                        }
                    }
                }
                for (String ref : fileRefs) {
                    discovered.computeIfAbsent(ref, k -> new LinkedHashSet<>()).add(h.getProjectName());
                }
            } catch (IOException ignored) {
                // 读不到就跳过该文件
            }
        }
        return discovered;
    }

    /**
     * 在指定项目范围内检索关键词
     *
     * @param projects 限定检索的项目列表
     * @param keywords 关键词列表（OR 关系；以 / 开头的视为 URL 路径，走精确匹配）
     * @return 命中文件列表，按相关性排序（路径命中 > 路由注解 > 命中次数），
     *         每个项目截取 topNPerProject 条
     */
    public List<SearchResult> search(List<ProjectInfo> projects, List<String> keywords) {
        if (keywords == null || keywords.isEmpty() || projects == null || projects.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalizedKw = keywords.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (normalizedKw.isEmpty()) return Collections.emptyList();

        // URL 路径派生变体：Spring 的类级 @RequestMapping + 方法级注解是拼接生效的，
        // 完整路径字符串在源码里往往不存在，必须拆成片段才能命中。
        // 原始完整路径 -> pathHit(100)；末两段80；方法名60；类级前缀20。
        Set<String> exactPaths = new LinkedHashSet<>();
        Map<String, Integer> kwPathScore = new LinkedHashMap<>();
        Set<String> expandedKw = new LinkedHashSet<>();
        for (String kw : normalizedKw) {
            if (kw.startsWith("/") && kw.length() > 1) {
                exactPaths.add(kw);
                expandedKw.add(kw);
                kwPathScore.merge(kw, 100, Math::max);
                String body = kw.substring(1);
                String[] seg = body.split("/");
                if (seg.length >= 2) {
                    addPathVariant(expandedKw, kwPathScore, "/" + seg[0] + "/" + seg[1], 20);                    // 类级前缀
                    addPathVariant(expandedKw, kwPathScore, "/" + seg[seg.length - 1], 60);                      // 末段方法名（带斜杠）
                    addPathVariant(expandedKw, kwPathScore, seg[seg.length - 1], 60);                            // 末段方法名（无斜杠，匹配 @RequestMapping("xxx") 写法）
                    if (seg.length >= 3) {
                        addPathVariant(expandedKw, kwPathScore, "/" + seg[seg.length - 2] + "/" + seg[seg.length - 1], 80); // 末两段
                    }
                }
            } else {
                expandedKw.add(kw);
            }
        }
        List<String> searchKw = new ArrayList<>(expandedKw);

        log.debug("开始检索，projects={}, keywords={} (含路径变体)",
                projects.stream().map(ProjectInfo::getName).toList(),
                searchKw);

        long started = System.currentTimeMillis();
        long hitsBefore = contentCacheHits.get();
        long missesBefore = contentCacheMisses.get();
        List<CompletableFuture<ProjectScanResult>> tasks = new ArrayList<>();
        for (ProjectInfo p : projects) {
            tasks.add(CompletableFuture.supplyAsync(
                    () -> searchProject(p, searchKw, exactPaths, kwPathScore), scanExecutor));
        }

        List<SearchResult> all = new ArrayList<>();
        int scannedFiles = 0;
        for (CompletableFuture<ProjectScanResult> task : tasks) {
            ProjectScanResult result = task.join();
            scannedFiles += result.scannedFiles();
            int topN = Math.min(searchProps.getTopNPerProject(), result.hits().size());
            all.addAll(result.hits().subList(0, topN));
        }
        // 全局再按相关性排序一次（路径命中 > 路由注解 > 命中次数）
        all.sort(RELEVANCE);
        long elapsed = System.currentTimeMillis() - started;
        log.info("关键词检索完成 projects={}, files={}, results={}, elapsedMs={}, cacheHits={}, cacheMisses={}",
                projects.size(), scannedFiles, all.size(), elapsed,
                contentCacheHits.get() - hitsBefore, contentCacheMisses.get() - missesBefore);
        return all;
    }

    private ProjectScanResult searchProject(ProjectInfo project, List<String> keywords,
                                            Set<String> exactPaths,
                                            Map<String, Integer> kwPathScore) {
        String absPath = project.resolveAbsolutePath(projectsProps.getCodeBase());
        Path root = Paths.get(absPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("项目目录不存在，跳过: {} ({})", project.getName(), absPath);
            return new ProjectScanResult(List.of(), 0);
        }
        return searchInProject(project, root, keywords, exactPaths, kwPathScore);
    }

    private void addPathVariant(Set<String> kws, Map<String, Integer> scores, String kw, int score) {
        if (kw == null || kw.isBlank()) return;
        kws.add(kw);
        scores.merge(kw, score, Math::max);
    }

    private ProjectScanResult searchInProject(ProjectInfo project, Path root,
                                              List<String> keywords, Set<String> exactPaths,
                                              Map<String, Integer> kwPathScore) {
        List<SearchResult> hits = new ArrayList<>();
        List<Path> files = projectFiles(root);
        int scanned = 0;
        for (Path file : files) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                SourceSnapshot source = sourceSnapshot(file);
                scanned++;
                Map<String, Integer> perKwHits = new LinkedHashMap<>();
                Set<Integer> hitLineSet = new TreeSet<>();
                boolean pathHit = false;
                boolean partialPathHit = false;
                int bestPathScore = 0;

                for (String kw : keywords) {
                    boolean kwIsPath = kw.startsWith("/");
                    SourceSnapshot.Match match = source.find(kw, kwIsPath, MAX_HIT_LINES);
                    if (match.lineCount() == 0) continue;
                    perKwHits.put(kw, match.lineCount());
                    for (int line : match.lines()) {
                        if (hitLineSet.size() >= MAX_HIT_LINES) break;
                        hitLineSet.add(line);
                    }
                    if (kwIsPath) {
                        int score = kwPathScore.getOrDefault(kw, 10);
                        if (score > bestPathScore) bestPathScore = score;
                        if (exactPaths.contains(kw)) pathHit = true;
                        else partialPathHit = true;
                    }
                }
                if (!perKwHits.isEmpty()) {
                    int total = perKwHits.values().stream().mapToInt(Integer::intValue).sum();
                    String topKw = perKwHits.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey).orElse(keywords.get(0));
                    hits.add(SearchResult.builder()
                            .filePath(file.toAbsolutePath().toString().replace('\\', '/'))
                            .projectName(project.getName())
                            .matchedKeyword(topKw)
                            .hitCount(total)
                            .hitLines(new ArrayList<>(hitLineSet))
                            .totalLines(source.totalLines())
                            .pathHit(pathHit)
                            .partialPathHit(partialPathHit)
                            .pathScore(bestPathScore)
                            .routeAnnotationCount(source.routeAnnotationCount())
                            .build());
                }
            } catch (IOException ignored) {
                // 文件可能刚被 Git checkout 删除，跳过即可；下次目录清单 TTL 到期后自动刷新。
            } catch (Exception e) {
                log.trace("文件检索异常 file={}", file, e);
            }
        }
        hits.sort(RELEVANCE);
        return new ProjectScanResult(hits, scanned);
    }

    private List<Path> projectFiles(Path root) {
        long now = System.currentTimeMillis();
        ProjectFileList cached = projectFileCache.get(root);
        if (cached != null && now - cached.loadedAtMs() <= Math.max(0, searchProps.getFileListCacheTtlMs())) {
            return cached.files();
        }

        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return searchProps.getExcludeDirs().contains(name)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (!isExcludedFile(name) && isCodeFile(name)) {
                        files.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历项目目录失败 root={}", root, e);
        }
        List<Path> immutable = List.copyOf(files);
        projectFileCache.put(root, new ProjectFileList(now, immutable));
        return immutable;
    }

    private SourceSnapshot sourceSnapshot(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        BasicFileAttributes attrs = Files.readAttributes(key, BasicFileAttributes.class);
        long size = attrs.size();
        long modified = attrs.lastModifiedTime().toMillis();

        synchronized (cacheLock) {
            SourceSnapshot cached = contentCache.get(key);
            if (cached != null && cached.matches(size, modified)) {
                contentCacheHits.incrementAndGet();
                return cached;
            }
            if (cached != null) {
                contentCache.remove(key);
                contentCacheBytes -= cached.estimatedBytes();
            }
        }

        String content = Files.readString(key, StandardCharsets.UTF_8);
        SourceSnapshot loaded = new SourceSnapshot(size, modified, content, countRouteAnnotations(content));

        synchronized (cacheLock) {
            // 并发请求可能已经装入同一版本，优先复用现有对象，避免重复占内存。
            SourceSnapshot existing = contentCache.get(key);
            if (existing != null && existing.matches(size, modified)) {
                contentCacheHits.incrementAndGet();
                return existing;
            }
            if (existing != null) contentCacheBytes -= existing.estimatedBytes();
            contentCache.put(key, loaded);
            contentCacheBytes += loaded.estimatedBytes();
            contentCacheMisses.incrementAndGet();
            evictContentCache();
        }
        return loaded;
    }

    private void evictContentCache() {
        int maxEntries = Math.max(1, searchProps.getContentCacheMaxEntries());
        long maxBytes = Math.max(16L, searchProps.getContentCacheMaxMb()) * 1024 * 1024;
        Iterator<Map.Entry<Path, SourceSnapshot>> it = contentCache.entrySet().iterator();
        while ((contentCache.size() > maxEntries || contentCacheBytes > maxBytes) && it.hasNext()) {
            SourceSnapshot removed = it.next().getValue();
            contentCacheBytes -= removed.estimatedBytes();
            it.remove();
        }
    }

    /**
     * 一份不可变文件快照：原文 + 小写副本 + 行起点。
     * 比缓存 String[] 原文/小写行更省对象；关键词用 indexOf 扫描，再用行起点还原行号。
     */
    private static final class SourceSnapshot {
        private final long size;
        private final long modified;
        private final String content;
        private final String lowerContent;
        private final int[] lineStarts;
        private final int routeAnnotationCount;
        private final long estimatedBytes;
        private volatile List<String> lines;

        private SourceSnapshot(long size, long modified, String content, int routeAnnotationCount) {
            this.size = size;
            this.modified = modified;
            this.content = content;
            this.lowerContent = content.toLowerCase(Locale.ROOT);
            this.lineStarts = buildLineStarts(content);
            this.routeAnnotationCount = routeAnnotationCount;
            // 保守估算两份字符串各 2 bytes/char，再加行索引和对象开销。
            this.estimatedBytes = content.length() * 4L + lineStarts.length * 4L + 128L;
        }

        private boolean matches(long expectedSize, long expectedModified) {
            return size == expectedSize && modified == expectedModified;
        }

        private String content() {
            return content;
        }

        private int totalLines() {
            return logicalLineCount();
        }

        private int routeAnnotationCount() {
            return routeAnnotationCount;
        }

        private long estimatedBytes() {
            return estimatedBytes;
        }

        private Match find(String keyword, boolean caseSensitive, int maxLines) {
            if (keyword == null || keyword.isEmpty() || keyword.indexOf('\n') >= 0 || keyword.indexOf('\r') >= 0) {
                return Match.EMPTY;
            }
            String haystack = caseSensitive ? content : lowerContent;
            String needle = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
            int from = 0;
            int lastLine = -1;
            int lineCount = 0;
            List<Integer> hitLines = new ArrayList<>();
            while (from <= haystack.length() - needle.length()) {
                int at = haystack.indexOf(needle, from);
                if (at < 0) break;
                int line = lineNumber(at);
                if (line != lastLine) {
                    lineCount++;
                    lastLine = line;
                    if (hitLines.size() < maxLines) hitLines.add(line);
                }
                from = at + Math.max(1, needle.length());
            }
            return lineCount == 0 ? Match.EMPTY : new Match(lineCount, hitLines);
        }

        private int lineNumber(int offset) {
            int pos = Arrays.binarySearch(lineStarts, offset);
            return pos >= 0 ? pos + 1 : -pos - 1;
        }

        private List<String> lines() {
            List<String> current = lines;
            if (current != null) return current;
            int logicalLineCount = logicalLineCount();
            List<String> built = new ArrayList<>(logicalLineCount);
            for (int i = 0; i < logicalLineCount; i++) {
                int start = lineStarts[i];
                int end = i + 1 < lineStarts.length ? lineStarts[i + 1] - 1 : content.length();
                if (end > start && content.charAt(end - 1) == '\r') end--;
                built.add(content.substring(start, Math.max(start, end)));
            }
            current = Collections.unmodifiableList(built);
            lines = current;
            return current;
        }

        /** 与 Files.readAllLines 保持一致：空文件和末尾换行都不额外产生空行。 */
        private int logicalLineCount() {
            if (content.isEmpty()) return 0;
            return content.charAt(content.length() - 1) == '\n'
                    ? lineStarts.length - 1 : lineStarts.length;
        }

        private static int[] buildLineStarts(String content) {
            int count = 1;
            for (int i = 0; i < content.length(); i++) if (content.charAt(i) == '\n') count++;
            int[] starts = new int[count];
            int cursor = 1;
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') starts[cursor++] = i + 1;
            }
            return starts;
        }

        private record Match(int lineCount, List<Integer> lines) {
            private static final Match EMPTY = new Match(0, List.of());
        }
    }

    /** 统计 Spring 路由注解出现次数，用于识别 Controller 文件 */
    private int countRouteAnnotations(String content) {
        if (content == null || content.isEmpty()) return 0;
        int count = 0;
        for (String ann : ROUTE_ANNOTATIONS) {
            int idx = 0;
            while ((idx = content.indexOf(ann, idx)) != -1) {
                count++;
                idx += ann.length();
            }
        }
        return count;
    }

    /**
     * 综合排序：路径具体度得分（完整路径100>末两段80>方法名60>类前缀20）
     * > 完整路径标记 > 片段命中标记 > 路由注解数（Controller 优先） > 命中行数。
     * 单个 reversed() 反转整条链，所有键均为降序。
     */
    private static final Comparator<SearchResult> RELEVANCE =
            Comparator.comparingInt(SearchResult::getPathScore)
                    .thenComparing(SearchResult::isPathHit)
                    .thenComparing(SearchResult::isPartialPathHit)
                    .thenComparing(SearchResult::getRouteAnnotationCount)
                    .thenComparing(SearchResult::getHitCount)
                    .reversed();

    private boolean isExcludedFile(String fileName) {
        return searchProps.getExcludeExts().stream().anyMatch(fileName::endsWith);
    }

    private boolean isCodeFile(String fileName) {
        // 这里只看后缀，扩展性留给后续。
        // 不含 .md：项目仓库里的需求/测试文档常整段粘贴源码且含完整 URL，
        // 会把真实源码挤出席位（文档检索走专门的 search_docs 工具）。
        return fileName.endsWith(".java")
                || fileName.endsWith(".xml")
                || fileName.endsWith(".yml") || fileName.endsWith(".yaml")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".sql");
    }

    /**
     * 读取指定文件的部分内容（带行号），最多 maxLines 行
     * 用于拼接到 LLM 上下文里
     */
    public String readFileWithLineNumbers(String filePath, int maxLines) {
        try {
            List<String> lines = sourceSnapshot(Paths.get(filePath)).lines();
            int limit = Math.min(maxLines, lines.size());
            StringBuilder sb = new StringBuilder(limit * 80);
            for (int i = 0; i < limit; i++) {
                sb.append(String.format("%4d| %s%n", i + 1, lines.get(i)));
            }
            if (limit < lines.size()) {
                sb.append("...（剩余 ").append(lines.size() - limit).append(" 行未展示）\n");
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("读取文件失败: {}", filePath, e);
            return "[读取失败] " + filePath;
        }
    }

    /**
     * 同项目类引用展开：解析锚点文件（如入口 Controller）里指向本项目的 import，
     * 在项目目录内定位这些类的源文件并返回。用于接口分析模式补充 DTO/Facade/Service 等关联类。
     *
     * @param anchor 锚点命中文件（取其 import 列表）
     * @param project 锚点所属项目（提供 packages 包根与检索根目录）
     * @param codeBase 代码根目录
     * @param limit 最多返回多少个类文件
     */
    public List<SearchResult> findSameProjectImports(SearchResult anchor, dev.example.productai.model.ProjectInfo project,
                                                     String codeBase, int limit) {
        try {
            String content = sourceSnapshot(Paths.get(anchor.getFilePath())).content();

            // 收集锚点全部 import 的类名并按"对链路分析的价值"打分：
            // DTO/请求响应对象、Facade/Service/Biz 核心协作类 > Feign/RPC Client > Enum/Utils/常量（负分丢弃）
            record Cand(String cls, String imp, int score, int order) {}
            List<Cand> cands = new ArrayList<>();
            Matcher m = IMPORT_PATTERN.matcher(content);
            int order = 0;
            while (m.find()) {
                String imp = m.group(1);
                String cls = imp.substring(imp.lastIndexOf('.') + 1).trim();
                order++;
                int score = 0;
                if (cls.matches(".*(:?DTO|Dto|Req|Request|VO|Vo|Param|Query|Form)$")) score += 4;
                if (cls.contains("Facade") || cls.endsWith("Service") || cls.endsWith("Biz")
                        || cls.endsWith("Mgr") || cls.endsWith("Manager")) score += 4;
                if (cls.endsWith("Client")) score += 3;
                if (cls.endsWith("Component") || cls.endsWith("Helper") || cls.endsWith("Builder")) score += 2;
                if (cls.endsWith("Enum") || cls.endsWith("Util") || cls.endsWith("Utils")
                        || cls.endsWith("Constant") || cls.endsWith("Constants")
                        || cls.endsWith("Config") || cls.endsWith("Properties")
                        || cls.endsWith("Exception") || cls.endsWith("Application")) score -= 2;
                if (score <= 0) continue;
                cands.add(new Cand(cls, imp, score, order));
            }
            cands.sort((x, y) -> y.score() != x.score()
                    ? Integer.compare(y.score(), x.score())
                    : Integer.compare(x.order(), y.order()));
            LinkedHashMap<String, Integer> wanted = new LinkedHashMap<>();
            for (Cand cd : cands) {
                if (wanted.size() >= Math.max(limit * 3, 8)) break;
                wanted.putIfAbsent(cd.cls(), wanted.size() + 1);
            }
            if (wanted.isEmpty()) return List.of();

            Path root = Paths.get(project.resolveAbsolutePath(codeBase));
            Map<String, Path> found = new LinkedHashMap<>();
            final boolean[] stop = {false};
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return searchProps.getExcludeDirs().contains(n)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".java")) return FileVisitResult.CONTINUE;
                    String base = name.substring(0, name.length() - ".java".length());
                    if (wanted.containsKey(base) && !found.containsKey(name)) {
                        found.put(name, f);
                        long missing = wanted.keySet().stream().filter(w -> !found.containsKey(w + ".java")).count();
                        if (found.size() >= limit || missing == 0) stop[0] = true;
                    }
                    return stop[0] ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });

            List<SearchResult> out = new ArrayList<>();
            for (Map.Entry<String, Path> e : found.entrySet()) {
                String clsName = e.getKey();
                try {
                    SourceSnapshot clsSource = sourceSnapshot(e.getValue());
                    String clsContent = clsSource.content();
                    List<String> arr = clsSource.lines();
                    Integer declLine = null;
                    for (int i = 0; i < arr.size(); i++) {
                        if (arr.get(i).matches(".*\\b(?:class|interface|enum|record)\\s+" + clsName + "\\b.*")) {
                            declLine = i + 1;
                            break;
                        }
                    }
                    out.add(SearchResult.builder()
                            .filePath(e.getValue().toAbsolutePath().toString().replace('\\', '/'))
                            .projectName(project.getName())
                            .matchedKeyword("import:" + anchor.getMatchedKeyword())
                            .hitCount(wanted.getOrDefault(clsName, 1))
                            .hitLines(declLine == null ? List.of() : List.of(declLine))
                             .totalLines(arr.size())
                            .pathHit(false).partialPathHit(false)
                            .pathScore(50) // 由锚点直接 import，可信度高于类前缀(20)
                            .routeAnnotationCount(countRouteAnnotations(clsContent))
                            .build());
                    if (out.size() >= limit) break;
                } catch (IOException ignored) {
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("同项目类引用展开失败 anchor={}", anchor.getFilePath(), e);
            return List.of();
        }
    }

    /**
     * 给 LLM 的代码上下文读取入口：
     * Java 文件按"完整方法"粒度切取（含注解与类声明），避免大方法被窗口拦腰截断；
     * 非 Java 文件或解析失败时退化为命中行窗口读取。
     */
    public String readCodeForLlm(String filePath, List<Integer> hitLines, int maxLines) {
        if (filePath.endsWith(".java") && hitLines != null && !hitLines.isEmpty()) {
            String r = extractJavaMethods(filePath, hitLines, maxLines);
            if (r != null) return r;
        }
        return readFileAroundHits(filePath, hitLines, maxLines);
    }

    /**
     * 定位每个命中行所属的完整方法体，按命中密度分配 maxLines 预算，整段装入。
     * 区间向上收编 @注解 行，另附类声明头一行，帮助 LLM 理解上下文归属。
     */
    private String extractJavaMethods(String filePath, List<Integer> hitLines, int maxLines) {
        try {
            List<String> lines = sourceSnapshot(Paths.get(filePath)).lines();
            int total = lines.size();
            TreeSet<Integer> hits = new TreeSet<>();
            for (Integer h : hitLines) if (h != null && h >= 1 && h <= total) hits.add(h);
            if (hits.isEmpty()) return null;

            // 1) 命中行 -> 所在方法区间；不在任何方法内的退化为小窗口
            TreeMap<Integer, int[]> byStart = new TreeMap<>();   // start -> {start,end,0}
            for (int h : hits) {
                int[] span = findEnclosingMethod(lines, h);
                if (span == null) span = new int[]{Math.max(1, h - 5), Math.min(total, h + 10), 0};
                byStart.putIfAbsent(span[0], span);
            }

            // 2) 合并重叠或相邻（间距<=4）的区间
            List<int[]> sorted = new ArrayList<>(byStart.values());
            sorted.sort(Comparator.comparingInt(a -> a[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] s : sorted) {
                if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1] + 4) {
                    int[] last = merged.get(merged.size() - 1);
                    last[1] = Math.max(last[1], s[1]);
                } else {
                    merged.add(new int[]{s[0], s[1], 0});
                }
            }
            for (int[] m : merged) {
                for (int h : hits) if (h >= m[0] && h <= m[1]) m[2]++;
            }

            // 3) 预算分配：命中多的方法优先整段装入；超大单方法允许截断
            List<int[]> order = new ArrayList<>(merged);
            order.sort((a, b) -> Integer.compare(b[2], a[2]));
            int classDecl = findClassDecl(lines);
            int used = 0;
            List<int[]> chosen = new ArrayList<>();
            for (int[] m : order) {
                int len = m[1] - m[0] + 1;
                int remain = maxLines - used;
                if (len <= remain) {
                    chosen.add(m);
                    used += len;
                } else if (remain >= 30) {
                    chosen.add(new int[]{m[0], m[0] + remain - 1, m[2], m[1]}); // 截断版
                    used = maxLines;
                    break;
                } else {
                    break;
                }
            }
            if (chosen.isEmpty()) return null;
            chosen.sort(Comparator.comparingInt(a -> a[0]));

            // 4) 渲染
            StringBuilder sb = new StringBuilder(maxLines * 80);
            sb.append("(文件共 ").append(total)
              .append(" 行；以下为关键词命中的完整方法/片段，行号为文件真实行号)\n");
            int prevEnd = 0;
            if (classDecl > 0 && chosen.get(0)[0] > classDecl) {
                sb.append(String.format("%4d| %s%n", classDecl, lines.get(classDecl - 1)));
                prevEnd = classDecl;
            }
            for (int[] m : chosen) {
                boolean truncated = m.length >= 4 && m[1] < m[3];
                if (m[0] > prevEnd + 1) {
                    sb.append("……（省略 ").append(m[0] - prevEnd - 1).append(" 行）\n");
                }
                for (int i = m[0]; i <= m[1]; i++) {
                    sb.append(String.format("%4d| %s%n", i, lines.get(i - 1)));
                }
                if (truncated) {
                    sb.append("……（该方法较长，剩余 ").append(m[3] - m[1]).append(" 行未展示）\n");
                }
                prevEnd = m[1];
            }
            if (prevEnd < total) {
                sb.append("……（文件其余部分省略）\n");
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("方法级读取失败，退化窗口模式: {}", filePath, e);
            return null;
        }
    }

    /**
     * 从命中行向上找所在方法的签名行，返回 {注解起始行, 方法结束行}（均 1-based）；找不到返回 null。
     * 启发式：签名 = 含圆括号且以 { 结尾（或下一行是 {），排除 if/for/switch/try 等控制语句；
     * 向上遇到类型声明仍未找到 => 命中在字段区，视为不在方法内。
     */
    private int[] findEnclosingMethod(List<String> lines, int lineNo) {
        int n = lines.size();
        int sig = -1;
        for (int i = lineNo - 1; i >= Math.max(0, lineNo - 1 - 80); i--) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.startsWith("class ") || low.startsWith("interface ")
                    || low.startsWith("enum ") || low.startsWith("record ")) return null;
            boolean parenPair = t.contains("(") && t.contains(")");
            boolean controlStmt = low.startsWith("if") || low.startsWith("for") || low.startsWith("while")
                    || low.startsWith("switch") || low.startsWith("catch") || low.startsWith("else")
                    || low.startsWith("try") || low.startsWith("synchronized") || low.startsWith("return ")
                    || low.startsWith("new ");
            if (parenPair && !controlStmt && !t.startsWith("@")) {
                if (t.endsWith("{")) { sig = i; break; }
                if (!t.endsWith(";") && i + 1 < n && lines.get(i + 1).trim().startsWith("{")) { sig = i; break; }
            }
        }
        if (sig < 0) return null;

        int start = sig;                                     // 向上收编注解行
        while (start - 1 >= 0 && lines.get(start - 1).trim().startsWith("@")) start--;

        int depth = 0, opened = 0, end = -1;                 // 花括号配对找方法结束
        for (int i = sig; i < n && i <= sig + 500; i++) {
            String clean = stripLineNoise(lines.get(i));
            for (char c : clean.toCharArray()) {
                if (c == '{') { depth++; opened++; }
                else if (c == '}') depth--;
            }
            if (opened > 0 && depth <= 0) { end = i; break; }
            if (opened == 0 && lines.get(i).trim().endsWith(";")) { end = i; break; }  // 抽象方法
        }
        if (end < 0) end = Math.min(n - 1, sig + 300);
        return new int[]{start + 1, end + 1};
    }

    /** 去掉字符串字面量/行注释/块注释残留，避免花括号误计数 */
    private String stripLineNoise(String s) {
        String r = s.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "")
                    .replaceAll("'(?:[^'\\\\]|\\\\.)*'", "");
        int lc = r.indexOf("//");
        if (lc >= 0) r = r.substring(0, lc);
        return r;
    }

    /** 找类型声明行（1-based），前 200 行内没有返回 -1 */
    private int findClassDecl(List<String> lines) {
        for (int i = 0; i < Math.min(lines.size(), 200); i++) {
            String t = lines.get(i).trim();
            if (t.matches("(?:public |final |abstract |sealed )*class\\s+\\w+.*\\{?")) return i + 1;
        }
        return -1;
    }

    /**
     * 围绕命中行读取文件片段：把命中行聚簇，取每簇前后各 CTX 行上下文，总预算 maxLines 行。
     * 解决"大文件只读头部、真正命中的方法在第 N 百行根本读不到"的问题。
     * 无命中行信息时退化为 readFileWithLineNumbers。
     */
    public String readFileAroundHits(String filePath, List<Integer> hitLines, int maxLines) {
        if (hitLines == null || hitLines.isEmpty()) {
            return readFileWithLineNumbers(filePath, maxLines);
        }
        try {
            List<String> all = sourceSnapshot(Paths.get(filePath)).lines();
            int total = all.size();
            List<int[]> windows = buildWindows(hitLines, total, maxLines);
            if (windows.isEmpty()) {
                return readFileWithLineNumbers(filePath, maxLines);
            }

            StringBuilder sb = new StringBuilder(maxLines * 80);
            int prevEnd = 0;
            for (int[] w : windows) {
                if (w[0] > prevEnd + 1) {
                    sb.append("……（省略 ").append(w[0] - prevEnd - 1).append(" 行）\n");
                }
                for (int i = w[0]; i <= w[1]; i++) {
                    sb.append(String.format("%4d| %s%n", i, all.get(i - 1)));
                }
                prevEnd = w[1];
            }
            if (prevEnd < total) {
                sb.append("……（省略 ").append(total - prevEnd).append(" 行，文件共 ").append(total).append(" 行）\n");
            }
            return sb.toString();
        } catch (IOException e) {
            log.warn("读取文件失败: {}", filePath, e);
            return "[读取失败] " + filePath;
        }
    }

    /**
     * 窗口规划：命中行按间距 <= MERGE_GAP 聚簇 → 每簇扩 CTX 行上下文 → 相邻窗口合并 →
     * 按簇内命中数从多到少分配预算（放不下的以命中段为中心截短），最后按位置排序输出。
     * 返回元素为 [startLine, endLine]（1-based，闭区间）。
     */
    private List<int[]> buildWindows(List<Integer> hitLines, int totalLines, int maxLines) {
        final int MERGE_GAP = 12;  // 命中间距不超过 12 行视为同一簇
        final int CTX = 25;        // 每簇前后各带多少行上下文

        // 1) 聚簇：记录每簇的起止命中行与命中个数
        List<int[]> clusters = new ArrayList<>();          // {hitStart, hitEnd, hitCount}
        int s = hitLines.get(0), e = s, cnt = 1;
        for (int i = 1; i < hitLines.size(); i++) {
            int h = hitLines.get(i);
            if (h <= totalLines && h - e <= MERGE_GAP) {
                e = h;
                cnt++;
            } else {
                clusters.add(new int[]{s, e, cnt});
                s = Math.min(h, totalLines);
                e = s;
                cnt = 1;
            }
        }
        clusters.add(new int[]{s, e, cnt});

        // 2) 扩展为窗口并合并重叠/相邻的
        List<int[]> wins = new ArrayList<>();              // {start, end, hitCount}
        for (int[] c : clusters) {
            int ws = Math.max(1, c[0] - CTX);
            int we = Math.min(totalLines, c[1] + CTX);
            if (!wins.isEmpty() && ws <= wins.get(wins.size() - 1)[1] + 1) {
                int[] last = wins.get(wins.size() - 1);
                last[1] = Math.max(last[1], we);
                last[2] += c[2];
            } else {
                wins.add(new int[]{ws, we, c[2]});
            }
        }

        // 3) 预算分配：命中多的窗口优先；放不下时以命中段为中心截短，再不行就丢弃
        List<int[]> sortedByHits = new ArrayList<>(wins);
        sortedByHits.sort((a, b) -> Integer.compare(b[2], a[2]));
        List<int[]> chosen = new ArrayList<>();
        int used = 0;
        for (int[] w : sortedByHits) {
            int len = w[1] - w[0] + 1;
            int remain = maxLines - used;
            if (remain <= 0) break;
            if (len <= remain) {
                chosen.add(new int[]{w[0], w[1]});
                used += len;
            } else if (remain >= 40) {
                int mid = (w[0] + w[1]) / 2;
                int st = Math.max(w[0], Math.min(mid - remain / 2, w[1] - remain + 1));
                st = Math.max(st, 1);
                chosen.add(new int[]{st, st + remain - 1});
                used = maxLines;
                break;
            } else {
                break;
            }
        }
        chosen.sort(Comparator.comparingInt(a -> a[0]));
        return chosen;
    }
}
