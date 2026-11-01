package dev.example.productai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.example.productai.config.RagProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小样本、真实 bge-m3 的切片 A/B。默认跳过，显式传 -DrunAstBenchmark=true 才运行。
 * 不读写 PostgreSQL，不会覆盖当前 rag_documents/rag_chunks。
 */
@Tag("benchmark")
class AstChunkingBenchmarkTest {

    private static final List<String> PROJECTS = List.of("sample-content-service", "sample-account-service");

    @Test
    void compareLegacyAndAstOnStratifiedRealSourceSample() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("runAstBenchmark"));
        Path codeBase = Paths.get(System.getProperty("codeBase", "./sample-repositories"));
        Path projectRoot = Paths.get(System.getProperty("projectRoot", "."));
        int samplesPerProject = Integer.getInteger("samplesPerProject", 8);
        String sampleSet = System.getProperty("sampleSet", "development");

        RagProperties props = new RagProperties();
        props.getIndex().setMaxChars(1_800);
        props.getIndex().setChunkLines(70);
        props.getEmbedding().setBaseUrl("http://127.0.0.1:11434");
        props.getEmbedding().setModel("bge-m3");
        props.getEmbedding().setBatchSize(8);
        props.getEmbedding().setTimeoutMs(300_000);

        Chunker legacy = new Chunker(props);
        AstJavaChunker ast = new AstJavaChunker(props);
        List<SourceFile> samples = selectSamples(codeBase, samplesPerProject, sampleSet);
        assertThat(samples).hasSize(PROJECTS.size() * samplesPerProject);

        Map<String, List<EvalChunk>> chunks = new LinkedHashMap<>();
        chunks.put("legacy", new ArrayList<>());
        chunks.put("ast", new ArrayList<>());
        List<QueryCase> queries = new ArrayList<>();
        List<String> sampledFiles = new ArrayList<>();
        long legacyNanos = 0;
        long astNanos = 0;
        int parsedFiles = 0;
        int eligibleDeclarations = 0;
        int legacyContained = 0;
        int astContained = 0;

        for (SourceFile sample : samples) {
            List<String> lines = Files.readAllLines(sample.path(), StandardCharsets.UTF_8);
            String className = Chunker.classNameOf(sample.path().getFileName().toString());
            String relPath = sample.project() + "/" + sample.root().relativize(sample.path()).toString().replace('\\', '/');
            sampledFiles.add(relPath);

            long start = System.nanoTime();
            List<Chunker.Chunk> legacyChunks = legacy.chunk(relPath, className, lines);
            legacyNanos += System.nanoTime() - start;
            start = System.nanoTime();
            AstJavaChunker.ChunkingResult astResult = ast.chunk(relPath, className, lines);
            astNanos += System.nanoTime() - start;
            if (astResult.parsed()) parsedFiles++;

            List<EvalChunk> legacyEval = wrap("legacy", relPath, legacyChunks);
            List<EvalChunk> astEval = wrap("ast", relPath, astResult.chunks());
            chunks.get("legacy").addAll(legacyEval);
            chunks.get("ast").addAll(astEval);

            List<AstJavaChunker.Declaration> eligible = astResult.declarations().stream()
                    .filter(d -> d.fits(props.getIndex().getMaxChars(), props.getIndex().getChunkLines(), lines))
                    .toList();
            eligibleDeclarations += eligible.size();
            legacyContained += (int) eligible.stream().filter(d -> containsWhole(legacyEval, relPath, d)).count();
            astContained += (int) eligible.stream().filter(d -> containsWhole(astEval, relPath, d)).count();

            eligible.stream()
                    .filter(d -> d.name().length() >= 4)
                    .limit(2)
                    .forEach(d -> queries.add(new QueryCase(
                            relPath + "#" + d.owner() + "." + d.name(),
                            "请定位 " + d.owner() + "." + d.name() + " 方法的完整实现",
                            relPath, d.startLine(), d.endLine())));
        }
        assertThat(queries.size()).isGreaterThanOrEqualTo(samples.size());

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        EmbeddingClient embedding = new EmbeddingClient(props, mapper);
        assertThat(embedding.ping()).as("本机 Ollama/bge-m3 必须可用").isTrue();

        Map<String, List<float[]>> vectors = new LinkedHashMap<>();
        for (Map.Entry<String, List<EvalChunk>> entry : chunks.entrySet()) {
            System.out.printf(Locale.ROOT, "[%s] embedding %d chunks%n", entry.getKey(), entry.getValue().size());
            vectors.put(entry.getKey(), embedInBatches(embedding,
                    entry.getValue().stream().map(EvalChunk::content).toList(), props.getEmbedding().getBatchSize()));
        }
        System.out.printf(Locale.ROOT, "[queries] embedding %d cases%n", queries.size());
        List<float[]> queryVectors = embedInBatches(embedding,
                queries.stream().map(QueryCase::text).toList(), props.getEmbedding().getBatchSize());

        Map<String, StrategyReport> reports = new LinkedHashMap<>();
        for (String strategy : chunks.keySet()) {
            RetrievalMetrics retrieval = evaluateRetrieval(chunks.get(strategy), vectors.get(strategy),
                    queries, queryVectors);
            double containment = ratio(strategy.equals("legacy") ? legacyContained : astContained,
                    eligibleDeclarations);
            double budgetCompliance = ratio(chunks.get(strategy).stream()
                            .filter(c -> c.content().length() <= props.getIndex().getMaxChars()
                                    && c.endLine() - c.startLine() + 1 <= props.getIndex().getChunkLines()).count(),
                    chunks.get(strategy).size());
            double parseAvailability = strategy.equals("ast") ? ratio(parsedFiles, samples.size()) : 1.0;
            double score = 100.0 * (0.35 * retrieval.recall1()
                    + 0.15 * retrieval.recall3()
                    + 0.20 * retrieval.mrr10()
                    + 0.20 * containment
                    + 0.05 * budgetCompliance
                    + 0.05 * parseAvailability);
            double millis = (strategy.equals("legacy") ? legacyNanos : astNanos) / 1_000_000.0;
            reports.put(strategy, new StrategyReport(strategy, chunks.get(strategy).size(),
                    chunks.get(strategy).stream().mapToInt(c -> c.content().length()).average().orElse(0),
                    millis, parseAvailability, containment, budgetCompliance,
                    retrieval.recall1(), retrieval.recall3(), retrieval.recall5(), retrieval.mrr10(), score));
        }

        StrategyReport legacyReport = reports.get("legacy");
        StrategyReport astReport = reports.get("ast");
        String winner = astReport.qualityScore() > legacyReport.qualityScore() ? "ast" : "legacy";
        String conclusion = conclusion(legacyReport, astReport);
        BenchmarkReport report = new BenchmarkReport(OffsetDateTime.now().toString(),
                "stratified-by-file-size-" + sampleSet, samples.size(), queries.size(), sampledFiles,
                "35% Recall@1 + 15% Recall@3 + 20% MRR@10 + 20% declaration containment + "
                        + "5% budget compliance + 5% parse availability",
                reports, winner, conclusion,
                "只做离线内存 A/B；没有写 PostgreSQL，也没有切换 RagIndexer。" );

        Path outputDir = projectRoot.resolve("output");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("ast-chunk-ab-result.json"),
                mapper.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("ast-chunk-ab-result.md"),
                toMarkdown(report), StandardCharsets.UTF_8);
        System.out.println(toMarkdown(report));
    }

    private static List<SourceFile> selectSamples(Path codeBase, int perProject, String sampleSet) throws Exception {
        List<SourceFile> result = new ArrayList<>();
        for (String project : PROJECTS) {
            Path root = codeBase.resolve(project);
            List<Path> candidates;
            try (var stream = Files.walk(root)) {
                candidates = stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> normalize(path).contains("/src/main/java/"))
                        .filter(path -> {
                            try {
                                long size = Files.size(path);
                                return size >= 800 && size <= 300_000;
                            } catch (Exception ignored) {
                                return false;
                            }
                        })
                        .sorted(Comparator.comparingLong(AstChunkingBenchmarkTest::safeSize)
                                .thenComparing(Path::toString))
                        .toList();
            }
            if (candidates.size() < perProject) {
                throw new IllegalStateException(project + " 可用 Java 文件不足: " + candidates.size());
            }
            Set<Path> chosen = new LinkedHashSet<>();
            for (int i = 0; i < perProject; i++) {
                double position = sampleSet.equalsIgnoreCase("holdout")
                        ? (i + 0.5) / perProject
                        : (double) i / (perProject - 1.0);
                int index = (int) Math.round(position * (candidates.size() - 1.0));
                chosen.add(candidates.get(index));
            }
            for (Path path : chosen) result.add(new SourceFile(project, root, path));
        }
        return result;
    }

    private static List<EvalChunk> wrap(String strategy, String file, List<Chunker.Chunk> source) {
        List<EvalChunk> out = new ArrayList<>(source.size());
        for (Chunker.Chunk chunk : source) {
            out.add(new EvalChunk(strategy, file, chunk.startLine(), chunk.endLine(), chunk.content()));
        }
        return out;
    }

    private static boolean containsWhole(List<EvalChunk> chunks, String file, AstJavaChunker.Declaration d) {
        return chunks.stream().anyMatch(c -> c.file().equals(file)
                && c.startLine() <= d.startLine() && c.endLine() >= d.endLine());
    }

    private static List<float[]> embedInBatches(EmbeddingClient client, List<String> texts, int batchSize) throws Exception {
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            result.addAll(client.embed(texts.subList(from, to)));
            if (to == texts.size() || to % 80 == 0) {
                System.out.printf(Locale.ROOT, "  embedded %d/%d%n", to, texts.size());
            }
        }
        return result;
    }

    private static RetrievalMetrics evaluateRetrieval(List<EvalChunk> chunks, List<float[]> vectors,
                                                      List<QueryCase> queries, List<float[]> queryVectors) {
        int hit1 = 0, hit3 = 0, hit5 = 0;
        double reciprocalRank = 0;
        for (int q = 0; q < queries.size(); q++) {
            QueryCase query = queries.get(q);
            float[] queryVector = queryVectors.get(q);
            List<RankedChunk> ranked = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                ranked.add(new RankedChunk(chunks.get(i), cosine(queryVector, vectors.get(i))));
            }
            ranked.sort(Comparator.comparingDouble(RankedChunk::score).reversed());
            int rank = 0;
            for (int i = 0; i < Math.min(10, ranked.size()); i++) {
                if (isRelevant(ranked.get(i).chunk(), query)) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank == 1) hit1++;
            if (rank > 0 && rank <= 3) hit3++;
            if (rank > 0 && rank <= 5) hit5++;
            if (rank > 0) reciprocalRank += 1.0 / rank;
        }
        return new RetrievalMetrics(ratio(hit1, queries.size()), ratio(hit3, queries.size()),
                ratio(hit5, queries.size()), reciprocalRank / queries.size());
    }

    private static boolean isRelevant(EvalChunk chunk, QueryCase query) {
        return chunk.file().equals(query.file())
                && chunk.startLine() <= query.endLine()
                && chunk.endLine() >= query.startLine();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / Math.sqrt(normA * normB);
    }

    private static String conclusion(StrategyReport legacy, StrategyReport ast) {
        double delta = ast.qualityScore() - legacy.qualityScore();
        double inflation = ratio(ast.chunkCount(), legacy.chunkCount()) - 1.0;
        if (delta >= 3.0 && inflation <= 0.30 && ast.parseAvailability() >= 0.95) {
            return "AST 在质量分上形成明确优势，且解析覆盖和切片膨胀可接受；建议进入更大留出集验证，仍不直接全量切换。";
        }
        if (delta > 0) {
            return "AST 有小幅优势，但不足以证明可替换现有策略；建议保留实验实现并扩大留出集。";
        }
        return "当前样本未证明 AST 优于现有策略；保持 legacy，不进入线上灰度。";
    }

    private static String toMarkdown(BenchmarkReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# Java 代码切片小样本 A/B 结果\n\n")
                .append("- 时间：").append(report.generatedAt()).append("\n")
                .append("- 样本：").append(report.sampleFiles()).append(" 个文件，")
                .append(report.queryCases()).append(" 个真实 bge-m3 检索问题\n")
                .append("- 范围：仅内存离线实验，未写现有向量表\n\n")
                .append("| 策略 | 质量分 | R@1 | R@3 | R@5 | MRR@10 | 声明完整率 | 预算合规 | 解析可用 | chunks | 切片耗时 ms |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (StrategyReport value : report.strategies().values()) {
            md.append(String.format(Locale.ROOT,
                    "| %s | %.2f | %.1f%% | %.1f%% | %.1f%% | %.3f | %.1f%% | %.1f%% | %.1f%% | %d | %.1f |%n",
                    value.strategy(), value.qualityScore(), value.recall1() * 100, value.recall3() * 100,
                    value.recall5() * 100, value.mrr10(), value.declarationContainment() * 100,
                    value.budgetCompliance() * 100, value.parseAvailability() * 100,
                    value.chunkCount(), value.chunkingMillis()));
        }
        md.append("\n## 结论\n\n").append(report.conclusion()).append("\n\n")
                .append("综合分公式：`").append(report.scoreFormula()).append("`。\n\n")
                .append("注意：小样本胜出只代表值得扩大验证，不等于允许全量迁移。\n");
        return md.toString();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private record SourceFile(String project, Path root, Path path) {}
    private record EvalChunk(String strategy, String file, int startLine, int endLine, String content) {}
    private record QueryCase(String id, String text, String file, int startLine, int endLine) {}
    private record RankedChunk(EvalChunk chunk, double score) {}
    private record RetrievalMetrics(double recall1, double recall3, double recall5, double mrr10) {}
    private record StrategyReport(String strategy, int chunkCount, double averageChars, double chunkingMillis,
                                  double parseAvailability, double declarationContainment,
                                  double budgetCompliance, double recall1, double recall3,
                                  double recall5, double mrr10, double qualityScore) {}
    private record BenchmarkReport(String generatedAt, String sampling, int sampleFiles, int queryCases,
                                   List<String> sampledPaths, String scoreFormula,
                                   Map<String, StrategyReport> strategies, String winner,
                                   String conclusion, String safety) {}
}
