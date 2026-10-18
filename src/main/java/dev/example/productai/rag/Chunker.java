package dev.example.productai.rag;

import dev.example.productai.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 切片器（v1 务实版）：
 * - 尽量按方法/类声明行切（签名行开新片），签名行之前积累不足则续片；
 * - 单片受 maxChars / chunkLines 双上限约束；
 * - 每片头部拼 context_header（文件路径 | 类名 | 行区间）——孤立代码片段
 *   embedding 效果差，带上下文是关键（设计稿 4.1）。
 *
 * @author wengzewei
 */
@Component
@RequiredArgsConstructor
public class Chunker {

    private final RagProperties props;

    /** 方法/类/字段声明行：作为切片边界候选 */
    private static final Pattern SIGNATURE = Pattern.compile(
            "^\\s{0,8}(public|private|protected|static|final|abstract|synchronized|class |interface |enum |record ).*");

    public record Chunk(String content, String contextHeader, int startLine, int endLine) {}

    public List<Chunk> chunk(String relPath, String className, List<String> lines) {
        int maxChars = props.getIndex().getMaxChars();
        int maxLines = props.getIndex().getChunkLines();
        List<Chunk> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curStart = 1;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            boolean boundary = SIGNATURE.matcher(line).matches() && cur.length() > 0 && cur.length() > maxChars / 3;
            if (boundary || cur.length() + line.length() > maxChars || (i - curStart + 1) >= maxLines) {
                if (cur.length() > 0) {
                    out.add(new Chunk(cur.toString(),
                            relPath + " | " + className + " | " + curStart + "-" + i + " 行",
                            curStart, i));
                }
                cur = new StringBuilder();
                curStart = i + 1;
            }
            cur.append(i + 1).append('|').append(line).append('\n');
            i++;
        }
        if (cur.length() > 0) {
            out.add(new Chunk(cur.toString(),
                    relPath + " | " + className + " | " + curStart + "-" + lines.size() + " 行",
                    curStart, lines.size()));
        }
        return out;
    }

    /** 从文件路径提取类名（v1 直接用文件名，内部类不单列） */
    public static String classNameOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
