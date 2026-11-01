package dev.example.productai.rag;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import dev.example.productai.config.RagProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * JavaParser 驱动的 Java 结构化切片器（实验实现）。
 *
 * <p>它当前没有接入 {@link RagIndexer}，只供离线 A/B 和单元测试使用。解析失败时会显式
 * 回退到现有 {@link Chunker}，因此未来即使灰度接入也不会因为新语法或损坏源码丢失文件。</p>
 */
public class AstJavaChunker {

    private static final int MAX_SIGNATURE_CHARS = 280;

    private final RagProperties props;
    private final Chunker fallback;
    private final JavaParser parser;

    public AstJavaChunker(RagProperties props) {
        this.props = props;
        this.fallback = new Chunker(props);
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setAttributeComments(true);
        this.parser = new JavaParser(configuration);
    }

    public record Declaration(String owner, String name, String kind, int startLine, int endLine) {
        public boolean fits(int maxChars, int maxLines, List<String> lines) {
            if (endLine - startLine + 1 > maxLines) return false;
            int chars = 0;
            for (int i = startLine - 1; i < endLine && i < lines.size(); i++) {
                chars += lines.get(i).length() + 8;
            }
            return chars <= maxChars;
        }
    }

    public record ChunkingResult(List<Chunker.Chunk> chunks, boolean parsed, boolean usedFallback,
                                 List<String> problems, List<Declaration> declarations) {}

    public ChunkingResult chunk(String relPath, String className, List<String> lines) {
        String source = String.join("\n", lines);
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            List<String> problems = parsed.getProblems().stream().map(Object::toString).toList();
            return new ChunkingResult(fallback.chunk(relPath, className, lines), false, true,
                    problems, List.of());
        }

        CompilationUnit unit = parsed.getResult().orElseThrow();
        String packageName = unit.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");
        List<CallableDeclaration<?>> callables = new ArrayList<>();
        callables.addAll(unit.findAll(MethodDeclaration.class));
        callables.addAll(unit.findAll(ConstructorDeclaration.class));
        callables.removeIf(callable -> callable.getRange().isEmpty()
                || callable.findAncestor(CallableDeclaration.class).isPresent());
        callables.sort(Comparator.comparingInt(c -> c.getRange().orElseThrow().begin.line));

        List<Chunker.Chunk> chunks = new ArrayList<>();
        List<Declaration> declarations = new ArrayList<>();
        int cursor = 1;
        for (CallableDeclaration<?> callable : callables) {
            var range = callable.getRange().orElseThrow();
            if (cursor < range.begin.line) {
                addBudgetedRange(chunks, relPath, className,
                        context(packageName, className, "type context"), lines,
                        cursor, range.begin.line - 1, "");
            }

            String owner = callable.findAncestor(TypeDeclaration.class)
                    .map(t -> t.getNameAsString())
                    .orElse(className);
            String kind = callable instanceof ConstructorDeclaration ? "constructor" : "method";
            String signature = compactSignature(callable);
            declarations.add(new Declaration(owner, callable.getNameAsString(), kind,
                    range.begin.line, range.end.line));
            addBudgetedRange(chunks, relPath, className,
                    context(packageName, owner, kind + " " + callable.getNameAsString()), lines,
                    range.begin.line, range.end.line, signature);
            cursor = Math.max(cursor, range.end.line + 1);
        }
        if (cursor <= lines.size()) {
            addBudgetedRange(chunks, relPath, className,
                    context(packageName, className, "type context"), lines,
                    cursor, lines.size(), "");
        }
        if (chunks.isEmpty() && !lines.isEmpty()) {
            addBudgetedRange(chunks, relPath, className,
                    context(packageName, className, "compilation unit"), lines,
                    1, lines.size(), "");
        }

        List<Chunker.Chunk> merged = mergeAdjacentSmallChunks(chunks, relPath, className);
        List<String> problems = parsed.getProblems().stream().map(Object::toString).toList();
        return new ChunkingResult(List.copyOf(merged), true, false, problems, List.copyOf(declarations));
    }

    /**
     * split-then-merge：AST 先保证声明边界，再把相邻的小节点装回同一个预算窗口。
     * 这避免“一方法一向量”导致块数和 embedding 成本失控，同时不会把半个方法截断。
     */
    private List<Chunker.Chunk> mergeAdjacentSmallChunks(List<Chunker.Chunk> source,
                                                          String relPath, String className) {
        if (source.size() < 2) return source;
        int maxChars = props.getIndex().getMaxChars();
        int maxLines = props.getIndex().getChunkLines();
        List<Chunker.Chunk> merged = new ArrayList<>();
        Chunker.Chunk current = source.getFirst();
        for (int i = 1; i < source.size(); i++) {
            Chunker.Chunk next = source.get(i);
            boolean adjacent = current.endLine() + 1 == next.startLine();
            boolean fitsLines = next.endLine() - current.startLine() + 1 <= maxLines;
            boolean fitsChars = current.content().length() + next.content().length() <= maxChars;
            if (adjacent && fitsLines && fitsChars) {
                current = new Chunker.Chunk(current.content() + next.content(),
                        relPath + " | " + className + " | " + current.startLine() + "-"
                                + next.endLine() + " 行 | AST merged",
                        current.startLine(), next.endLine());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private void addBudgetedRange(List<Chunker.Chunk> out, String relPath, String className,
                                  String context, List<String> lines, int start, int end,
                                  String repeatedSignature) {
        if (start > end || start < 1 || start > lines.size()) return;
        int boundedEnd = Math.min(end, lines.size());
        int maxChars = props.getIndex().getMaxChars();
        int maxLines = props.getIndex().getChunkLines();
        int cursor = start;
        boolean first = true;
        while (cursor <= boundedEnd) {
            StringBuilder content = new StringBuilder();
            appendSynthetic(content, "// AST context: " + context, maxChars);
            if (!first && !repeatedSignature.isBlank()) {
                appendSynthetic(content, "// declaration: " + repeatedSignature, maxChars);
            }
            int chunkStart = cursor;
            int actualLines = 0;
            while (cursor <= boundedEnd && actualLines < maxLines) {
                String rendered = cursor + "|" + lines.get(cursor - 1) + "\n";
                if (content.length() + rendered.length() > maxChars && actualLines > 0) break;
                if (content.length() + rendered.length() > maxChars) {
                    // 极端的单行超长源码宁可暂时超预算，也不能静默截断并丢失代码。
                    // budgetCompliance 会把该情况暴露在评测里，后续可专门做行内切割。
                    content.append(rendered);
                    cursor++;
                    actualLines++;
                    break;
                }
                content.append(rendered);
                cursor++;
                actualLines++;
            }
            int chunkEnd = Math.max(chunkStart, cursor - 1);
            out.add(new Chunker.Chunk(content.toString(),
                    relPath + " | " + className + " | " + chunkStart + "-" + chunkEnd + " 行 | AST",
                    chunkStart, chunkEnd));
            first = false;
        }
    }

    private static void appendSynthetic(StringBuilder target, String text, int maxChars) {
        if (text == null || text.isBlank() || target.length() >= maxChars - 1) return;
        int available = maxChars - target.length() - 1;
        target.append(text, 0, Math.min(available, text.length())).append('\n');
    }

    private static String compactSignature(CallableDeclaration<?> callable) {
        String signature;
        if (callable instanceof MethodDeclaration method) {
            signature = method.getDeclarationAsString(true, true, true);
        } else if (callable instanceof ConstructorDeclaration constructor) {
            signature = constructor.getDeclarationAsString(true, true, true);
        } else {
            signature = callable.getNameAsString();
        }
        signature = signature.replaceAll("\\s+", " ").trim();
        if (signature.length() > MAX_SIGNATURE_CHARS) {
            return signature.substring(0, MAX_SIGNATURE_CHARS) + "...";
        }
        return signature;
    }

    private static String context(String packageName, String owner, String symbol) {
        String qualifiedOwner = packageName.isBlank() ? owner : packageName + "." + owner;
        return (qualifiedOwner + " | " + symbol).toLowerCase(Locale.ROOT);
    }
}
