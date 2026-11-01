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
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "\\b(?:public|protected|private|default)\\s+(?:[A-Za-z0-9_$.,?<>\\[\\]]+\\s+)+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    /** 高置信跨服务/仓储调用；模型漏登记时由程序补成 pending，防止“零 pending 假闭环”。 */
    private static final Pattern OUTBOUND_CALL = Pattern.compile(
            "\\b([A-Za-z_$][A-Za-z0-9_$]*(?:FeignClient|RpcRepository|RemoteClient|RpcClient))\\s*\\.\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
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
                    "(?i)(?:@SampleSendMessage\\b|\\b(?:rocketMQTemplate|kafkaTemplate|rabbitTemplate|defaultMQProducer)\\b(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)?)")),
            new TerminalRule("file", Pattern.compile(
                    "(?i)\\b(ossClient|minioClient|s3Client)\\b(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*)?"))
    );

    public Extraction collect(TraceState state, String file, String numberedContent) {
        if (file == null || file.isBlank() || numberedContent == null || numberedContent.isBlank()) {
            return new Extraction(0, 0);
        }
        int resolved = resolveVisitedTarget(state, file, numberedContent);
        int terminals = 0;
        String sourceType = sourceType(file);
        for (String rawLine : numberedContent.split("\\R")) {
            Matcher numbered = NUMBERED_LINE.matcher(rawLine);
            if (!numbered.matches()) continue;
            int line = Integer.parseInt(numbered.group(1));
            String code = numbered.group(2);
            if (isCommentOnly(code)) continue;
            discoverOutboundEdges(state, sourceType, file, line, code);
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

    private void discoverOutboundEdges(TraceState state, String sourceType, String file, int line, String code) {
        Matcher matcher = OUTBOUND_CALL.matcher(code);
        while (matcher.find()) {
            String collaborator = matcher.group(1);
            String method = matcher.group(2);
            TraceEdge edge = TraceEdge.builder()
                    .fromSymbol(sourceType)
                    .toSymbol(collaborator + "." + method)
                    .kind(collaborator.contains("FeignClient") || collaborator.endsWith("Client") ? "feign" : "rpc")
                    .status("pending")
                    .evidenceFile(file)
                    .evidenceLine(line)
                    .build();
            // 重放材料时不能把模型已经确认 resolved/excluded 的同一条边覆盖回 pending。
            state.getEdges().putIfAbsent(edge.identity(), edge);
        }
    }

    private int resolveVisitedTarget(TraceState state, String file, String numberedContent) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        String normalizedFileType = normalizeType(name);
        var declaredMethods = declaredMethods(numberedContent);
        int resolved = 0;
        for (TraceEdge edge : state.getEdges().values()) {
            if (!edge.isPending()) continue;
            String targetType = targetType(edge.getToSymbol());
            boolean typeMatched = !targetType.isBlank() && normalizeType(targetType).equals(normalizedFileType);
            String targetMethod = targetMethod(edge.getToSymbol(), declaredMethods);
            boolean methodMatched = targetMethod != null;
            if (!typeMatched && !methodMatched) continue;
            edge.setStatus("resolved");
            // 方法级回退通常是在提供方 Controller 中命中，必须把证据切换到实现文件，
            // 不能继续保留调用方 Component/Feign 的旧路径。
            if (methodMatched || edge.getEvidenceFile() == null || edge.getEvidenceFile().isBlank()) {
                edge.setEvidenceFile(file);
            }
            resolved++;
        }
        return resolved;
    }

    private java.util.Set<String> declaredMethods(String numberedContent) {
        java.util.Set<String> methods = new java.util.LinkedHashSet<>();
        for (String rawLine : numberedContent.split("\\R")) {
            Matcher numbered = NUMBERED_LINE.matcher(rawLine);
            if (!numbered.matches() || isCommentOnly(numbered.group(2))) continue;
            Matcher declaration = METHOD_DECLARATION.matcher(numbered.group(2));
            if (declaration.find()) methods.add(declaration.group(1));
        }
        return methods;
    }

    private String targetMethod(String target, java.util.Set<String> declaredMethods) {
        if (target == null || declaredMethods.isEmpty()) return null;
        Matcher token = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(target);
        while (token.find()) {
            String value = token.group();
            // query/save 等短方法在大型代码库中重名率很高，只允许足够具体的方法名跨类型消歧。
            if (value.length() >= 12 && declaredMethods.contains(value)) return value;
        }
        return null;
    }

    private String targetType(String target) {
        if (target == null) return "";
        String compact = target.split("[（(\\s]", 2)[0];
        String[] parts = compact.split("[.#]");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.matches(".*(?:FeignClient|RpcRepository|RemoteClient|RpcClient)$")) return part;
        }
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i];
            if (!part.isBlank() && Character.isUpperCase(part.charAt(0))) return part;
        }
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private String sourceType(String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
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
