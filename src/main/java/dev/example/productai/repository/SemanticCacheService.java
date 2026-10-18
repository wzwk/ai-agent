package dev.example.productai.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.model.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义缓存（v0.9 词法版 → v0.12 向量升级）：
 * - 相似度：**向量优先**（bge-m3 embed 原始问题，pgvector 余弦）——换措辞的重复提问
 *   词法 Jaccard 抓不住（"怎么处理"vs"要怎么清理"），向量能抓；
 *   Ollama 不可用/旧行无 embedding 时**词法兜底**（bigram+unigram Jaccard）；
 * - 只缓存 confidence=high、!needHuman 且 completionStatus=complete 的高质量答案（低质量进缓存是错误放大器，
 *   2026-08-31 两次实战教训）；
 * - 时效性三道防线：TTL 过期弃用 + 引用文件仍存在校验 + 命中响应显式标注"历史解答"；
 * - 模式参与判定：同题不同模式（user/dev）答案格式不同，不互通；
 * - 缓存全局共享：跨用户/跨会话命中（查询只按 mode+TTL 过滤，不看 sessionId）。
 *
 * @author wengzewei
 */
@Slf4j
@Service
public class SemanticCacheService {

    private final JdbcTemplate jdbc;
    private final ProjectsProperties projectsProps;
    private final dev.example.productai.rag.EmbeddingClient embedding;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.semantic-cache.enabled:true}")
    private boolean enabled;
    @Value("${app.semantic-cache.ttl-hours:72}")
    private int ttlHours;
    /** 词法 bigram Jaccard 阈值：宁紧勿松（"商品总数不对"≠"商品总数怎么统计"） */
    @Value("${app.semantic-cache.threshold:0.93}")
    private double threshold;
    /** 向量余弦阈值：bge-m3 对同一问题的换措辞通常 0.85+，不同问题一般 <0.75 */
    @Value("${app.semantic-cache.vector-threshold:0.87}")
    private double vectorThreshold;
    /** 每次扫描该模式最近的缓存条数（Java 侧算相似度，控制计算量） */
    @Value("${app.semantic-cache.scan-limit:300}")
    private int scanLimit;

    public SemanticCacheService(JdbcTemplate jdbc, ProjectsProperties projectsProps,
                                dev.example.productai.rag.EmbeddingClient embedding) {
        this.jdbc = jdbc;
        this.projectsProps = projectsProps;
        this.embedding = embedding;
    }

    /** 命中结果：answer + 元数据，供 ProductAgent 组装响应 */
    public record CacheHit(String answer, String confidence, List<String> sourceFiles,
                           List<ChatResponse.Step> steps, String engine, Integer stepsUsed, int hits) {}

    private volatile long lastFailEpoch = 0;
    private static final long RETRY_BACKOFF_MS = 5 * 60_000L;
    private boolean shouldTry() { return enabled && System.currentTimeMillis() - lastFailEpoch > RETRY_BACKOFF_MS; }

    // ==================================================================================
    // 读
    // ==================================================================================

    /**
     * 状态图回答的保守快路径：只接受同模式、同归一化问题、trace 引擎且 COMPLETE 的答案，
     * 并要求所有引用源码自缓存生成后都没有修改。它不做语义近似匹配，避免把“相似但分支不同”
     * 或历史未闭环答案直接复用。
     */
    public CacheHit findVerifiedExact(String question, String mode) {
        if (!shouldTry()) return null;
        String norm = normalize(question);
        if (norm.isBlank()) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, answer, confidence, source_files, steps_json, engine, steps_used,
                           hit_count, created_at
                    FROM answer_cache
                    WHERE question_norm = ? AND mode = ? AND engine = 'trace'
                      AND completion_status = 'complete'
                      AND created_at > now() - make_interval(hours => ?)
                    ORDER BY created_at DESC
                    LIMIT 5
                    """, norm, mode, ttlHours);
            for (Map<String, Object> row : rows) {
                List<String> files = parseStrArray(String.valueOf(row.get("source_files")));
                Instant createdAt = toInstant(row.get("created_at"));
                if (files.isEmpty() || createdAt == null || !citedFilesUnchanged(files, createdAt)) continue;
                jdbc.update("UPDATE answer_cache SET hit_count = hit_count + 1, last_used_at = now() WHERE id = ?",
                        ((Number) row.get("id")).longValue());
                log.info("状态图精确缓存命中：引用源码未变化，历史命中 {} 次", row.get("hit_count"));
                return new CacheHit(
                        String.valueOf(row.get("answer")),
                        row.get("confidence") == null ? "high" : String.valueOf(row.get("confidence")),
                        files, parseSteps(String.valueOf(row.get("steps_json"))), "trace",
                        row.get("steps_used") == null ? null : ((Number) row.get("steps_used")).intValue(),
                        row.get("hit_count") == null ? 1 : ((Number) row.get("hit_count")).intValue() + 1);
            }
            return null;
        } catch (Exception e) {
            lastFailEpoch = System.currentTimeMillis();
            log.warn("状态图精确缓存读取失败（忽略，重新调查）: {}", e.getMessage());
            return null;
        }
    }

    /** 查缓存：向量优先（换措辞命中）→ 词法兜底（Ollama 挂/旧行无向量） */
    public CacheHit find(String question, String mode) {
        if (!shouldTry()) return null;
        try {
            // ---- 第一路：向量相似度（bge-m3 embed 原始问题）----
            Map<String, Object> best = null;
            double bestSim = 0;
            String hitPath = "词法";
            try {
                String qv = dev.example.productai.rag.EmbeddingClient.toPgVector(embedding.embedOne(question));
                List<Map<String, Object>> vrows = jdbc.queryForList("""
                        SELECT id, question_raw, answer, confidence, source_files, steps_json, engine, steps_used, hit_count,
                               1 - (embedding <=> ?::vector) AS sim
                        FROM answer_cache
                        WHERE mode = ? AND created_at > now() - make_interval(hours => ?)
                          AND completion_status = 'complete'
                          AND embedding IS NOT NULL
                        ORDER BY embedding <=> ?::vector
                        LIMIT 3
                        """, qv, mode, ttlHours, qv);
                if (!vrows.isEmpty()) {
                    double sim = ((Number) vrows.get(0).get("sim")).doubleValue();
                    if (sim >= vectorThreshold) {
                        best = vrows.get(0);
                        bestSim = sim;
                        hitPath = "向量";
                    }
                }
            } catch (Exception ve) {
                log.debug("向量缓存检索不可用，走词法兜底：{}", ve.getMessage());
            }

            // ---- 第二路：词法兜底（向量没命中/不可用时）----
            if (best == null) {
                String norm = normalize(question);
                if (norm.isEmpty()) return null;
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT id, question_raw, answer, confidence, source_files, steps_json, engine, steps_used, hit_count
                        FROM answer_cache
                        WHERE mode = ? AND created_at > now() - make_interval(hours => ?)
                          AND completion_status = 'complete'
                        ORDER BY last_used_at DESC LIMIT ?
                        """, mode, ttlHours, scanLimit);
                Set<String> qBigrams = bigrams(norm);
                Set<String> qUnigrams = unigrams(norm);
                for (Map<String, Object> row : rows) {
                    // 用当前归一化算法重算候选（历史行的 question_norm 可能是旧算法产物）
                    String candNorm = normalize(String.valueOf(row.get("question_raw")));
                    double sim = candNorm.equals(norm) ? 1.0
                            : Math.max(jaccard(qBigrams, bigrams(candNorm)),
                                       jaccard(qUnigrams, unigrams(candNorm)));
                    if (sim > bestSim) { bestSim = sim; best = row; }
                }
                if (best == null || bestSim < threshold) return null;
            }
            List<String> files = parseStrArray(String.valueOf(best.get("source_files")));
            if (!files.isEmpty() && !citedFilesExist(files)) {
                log.info("语义缓存命中但引用文件已变动，放弃（sim={}）", bestSim);
                return null;
            }
            int hits = jdbc.update("UPDATE answer_cache SET hit_count = hit_count + 1, last_used_at = now() WHERE id = ?",
                    ((Number) best.get("id")).longValue());
            log.info("语义缓存命中（{}）：sim={}，历史命中 {} 次", hitPath, String.format("%.3f", bestSim),
                    best.get("hit_count"));
            return new CacheHit(
                    String.valueOf(best.get("answer")),
                    best.get("confidence") == null ? "medium" : String.valueOf(best.get("confidence")),
                    files,
                    parseSteps(String.valueOf(best.get("steps_json"))),
                    best.get("engine") == null ? null : String.valueOf(best.get("engine")),
                    best.get("steps_used") == null ? null : ((Number) best.get("steps_used")).intValue(),
                    hits);
        } catch (Exception e) {
            lastFailEpoch = System.currentTimeMillis();
            log.warn("语义缓存读失败（忽略，正常回答）: {}", e.getMessage());
            return null;
        }
    }

    // ==================================================================================
    // 写（只存高质量答案）
    // ==================================================================================

    public void store(String question, String mode, ChatResponse resp) {
        if (!shouldTry() || resp == null) return;
        if (!"high".equalsIgnoreCase(resp.getConfidence()) || resp.isNeedHuman()) return;
        String completionStatus = resp.getCompletionStatus();
        if ("partial".equalsIgnoreCase(completionStatus)) return;
        // agent 回答必须显式证明已闭环；workflow 历史管线没有该字段，按完整回答兼容。
        if ("agent".equalsIgnoreCase(resp.getEngine()) && !"complete".equalsIgnoreCase(completionStatus)) return;
        if (completionStatus == null || completionStatus.isBlank()) completionStatus = "complete";
        try {
            List<Map<String, Object>> steps = new ArrayList<>();
            if (resp.getSteps() != null) {
                for (ChatResponse.Step st : resp.getSteps()) {
                    steps.add(Map.of(
                            "no", st.getNo(), "phase", nvl(st.getPhase()), "tool", nvl(st.getTool()),
                            "args", nvl(st.getArgs()), "summary", nvl(st.getSummary()),
                            "elapsedMs", st.getElapsedMs(), "status", nvl(st.getStatus())));
                }
            }
            jdbc.update("""
                    INSERT INTO answer_cache
                      (question_norm, question_raw, mode, answer, confidence, source_files, steps_json,
                       engine, steps_used, completion_status, embedding)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::vector)
                    """,
                    normalize(question), question, mode, resp.getAnswer(), resp.getConfidence(),
                    toJsonArray(resp.getSourceFiles()), mapper.writeValueAsString(steps),
                    resp.getEngine(), resp.getStepsUsed(), completionStatus, embedOrNull(question));
            log.info("语义缓存已存入（mode={}，{} 步轨迹）", mode, steps.size());
        } catch (Exception e) {
            lastFailEpoch = System.currentTimeMillis();
            log.warn("语义缓存写失败（忽略）: {}", e.getMessage());
        }
    }

    // ==================================================================================
    // 相似度与工具
    // ==================================================================================

    /**
     * 归一化：去接口分析前缀/空白/标点，转小写，再剔掉常见虚词字。
     * 虚词剔除是关键："实现在哪个类" vs "实现是在哪个类里面的"——去虚词后完全一致。
     */
    private static final String PARTICLES = "的是在了着呢吗吧啊呀么哩哪些里面把被和跟于对与又就都很也还";

    static String normalize(String q) {
        if (q == null) return "";
        String s = q.replace("【接口分析】", "");
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (!Character.isLetterOrDigit(c)) continue;       // 标点空白丢弃
            if (PARTICLES.indexOf(c) >= 0) continue;           // 虚词字丢弃
            sb.append(c);
        }
        return sb.toString();
    }

    /** 字符 bigram 集合（中文短文本的近重复检测够用） */
    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        if (s.length() < 2) { if (!s.isEmpty()) set.add(s); return set; }
        for (int i = 0; i < s.length() - 1; i++) set.add(s.substring(i, i + 2));
        return set;
    }

    private static Set<String> unigrams(String s) {
        Set<String> set = new HashSet<>();
        for (char c : s.toCharArray()) set.add(String.valueOf(c));
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String x : a) if (b.contains(x)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    /** 引用文件仍存在校验（删除/改名会让旧答案失效；内容变化靠 TTL 兜底） */
    private boolean citedFilesExist(List<String> files) {
        Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
        for (String f : files) {
            if (f == null || f.isBlank() || f.startsWith("docs/")) continue;   // 文档不走代码库校验
            if (!Files.exists(base.resolve(f.replace('\\', '/')))) return false;
        }
        return true;
    }

    private boolean citedFilesUnchanged(List<String> files, Instant createdAt) {
        Path base = Paths.get(projectsProps.getCodeBase()).toAbsolutePath().normalize();
        for (String f : files) {
            if (f == null || f.isBlank() || f.startsWith("docs/")) continue;
            Path path = base.resolve(f.replace('\\', '/')).normalize();
            try {
                if (!path.startsWith(base) || !Files.isRegularFile(path)
                        || Files.getLastModifiedTime(path).toInstant().isAfter(createdAt)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.util.Date date) return date.toInstant();
        if (value instanceof OffsetDateTime time) return time.toInstant();
        if (value instanceof LocalDateTime time) return time.atZone(ZoneId.systemDefault()).toInstant();
        return null;
    }

    private String toJsonArray(List<String> files) {
        StringBuilder sb = new StringBuilder("[");
        if (files != null) {
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(mapper.valueToTree(files.get(i)).toString());
            }
        }
        return sb.append(']').toString();
    }

    private List<String> parseStrArray(String raw) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode n = mapper.readTree(raw == null ? "[]" : raw);
            if (n.isArray()) n.forEach(x -> out.add(x.asText()));
        } catch (Exception ignored) {
        }
        return out;
    }

    private List<ChatResponse.Step> parseSteps(String raw) {
        try {
            JsonNode n = mapper.readTree(raw == null ? "[]" : raw);
            List<ChatResponse.Step> out = new ArrayList<>();
            if (n.isArray()) n.forEach(x -> out.add(ChatResponse.Step.builder()
                    .no(x.path("no").asInt(0))
                    .phase(x.path("phase").asText(""))
                    .tool(x.path("tool").asText(null))
                    .args(x.path("args").asText(null))
                    .summary(x.path("summary").asText(null))
                    .elapsedMs(x.path("elapsedMs").asLong(0))
                    .status(x.path("status").asText("ok"))
                    .build()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    /** 问题向量（Ollama 挂了就存 NULL，读侧自动落到词法兜底；不影响答案入库） */
    private String embedOrNull(String question) {
        try {
            return dev.example.productai.rag.EmbeddingClient.toPgVector(embedding.embedOne(question));
        } catch (Exception e) {
            log.debug("问题 embedding 失败，该条走词法：{}", e.getMessage());
            return null;
        }
    }
}
