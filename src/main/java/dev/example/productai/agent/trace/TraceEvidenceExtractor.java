package dev.example.productai.agent.trace;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从已经实际读取的源码行中提取确定性终点，并把“目标实现文件已经读过”的 pending edge 标为 resolved。
 * 这里只识别具体客户端/Repository/Mapper 调用，不把 Feign、Service 或普通 Component 当终点。
 */
@Component
public class TraceEvidenceExtractor {

    private static final Pattern NUMBERED_LINE = Pattern.compile("^(\\d+)\\|(.*)$");
    private static final List<TerminalRule> TERMINAL_RULES = List.of(
            new TerminalRule("mongo", Pattern.compile(
                    "(?i)\\b([A-Za-z0-9_]*(?:MongoRepository|MongoDao|MongoDAO)|mongoTemplate|mongoOperations|mongoCollection)\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*")),
            new TerminalRule("mysql", Pattern.compile(
                    "(?i)\\b(jdbcTemplate|[A-Za-z0-9_]*Mapper)\\s*\\.\\s*(?:insert|update|delete|select|save|batch)[A-Za-z0-9_]*")),
            new TerminalRule("redis", Pattern.compile(
                    "(?i)\\b(redisTemplate|redissonClient|[A-Za-z0-9_]*RedisRepository|RLock)\\b(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)?")),
            new TerminalRule("es", Pattern.compile(
                    "(?i)\\b(elasticsearchOperations|restHighLevelClient|elasticSearchClient|[A-Za-z0-9_]*EsRepository)\\b(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)?")),
            new TerminalRule("mq", Pattern.compile(
                    "(?i)\\b(rocketMQTemplate|kafkaTemplate|rabbitTemplate)\\b(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)?")),
            new TerminalRule("file", Pattern.compile(
                    "(?i)\\b(ossClient|minioClient|s3Client)\\b(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)?"))
    );

    public Extraction collect(TraceState state, String file, String numberedContent) {
        if (file == null || file.isBlank() || numberedContent == null || numberedContent.isBlank()) {
            return new Extraction(0, 0);
        }
        int resolved = resolveVisitedTarget(state, file);
        int terminals = 0;
        for (String rawLine : numberedContent.split("\\R")) {
            Matcher numbered = NUMBERED_LINE.matcher(rawLine);
            if (!numbered.matches()) continue;
            int line = Integer.parseInt(numbered.group(1));
            String code = numbered.group(2);
            if (isCommentOnly(code)) continue;
            for (TerminalRule rule : TERMINAL_RULES) {
                Matcher terminal = rule.pattern().matcher(code);
                if (!terminal.find()) continue;
                String symbol = terminal.group().replaceAll("\\s+", "");
                int before = state.getTerminals().size();
                state.putTerminal(TraceTerminal.builder()
                        .symbol(symbol + "@" + line)
                        .type(rule.type())
                        .evidenceFile(file)
                        .evidenceLine(line)
                        .detail(code.trim())
                        .build());
                if (state.getTerminals().size() > before) terminals++;
                break;
            }
        }
        return new Extraction(resolved, terminals);
    }

    private int resolveVisitedTarget(TraceState state, String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        String normalizedFileType = normalizeType(name);
        int resolved = 0;
        for (TraceEdge edge : state.getEdges().values()) {
            if (!edge.isPending()) continue;
            String targetType = targetType(edge.getToSymbol());
            if (targetType.isBlank() || !normalizeType(targetType).equals(normalizedFileType)) continue;
            edge.setStatus("resolved");
            if (edge.getEvidenceFile() == null || edge.getEvidenceFile().isBlank()) edge.setEvidenceFile(file);
            resolved++;
        }
        return resolved;
    }

    private String targetType(String target) {
        if (target == null) return "";
        String compact = target.split("[（(\\s]", 2)[0];
        String[] parts = compact.split("[.#]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (!part.isBlank() && Character.isUpperCase(part.charAt(0))) return part;
        }
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private String normalizeType(String type) {
        String value = type == null ? "" : type.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (value.startsWith("i") && value.length() > 1 && Character.isUpperCase(type.length() > 1 ? type.charAt(1) : 'x')) {
            return value.substring(1);
        }
        return value;
    }

    private boolean isCommentOnly(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
    }

    public record Extraction(int resolvedEdges, int terminals) {}
    private record TerminalRule(String type, Pattern pattern) {}
}
