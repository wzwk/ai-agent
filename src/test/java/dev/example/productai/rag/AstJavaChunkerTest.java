package dev.example.productai.rag;

import dev.example.productai.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AstJavaChunkerTest {

    @Test
    void keepsAnnotationsMultilineSignatureAndMethodBodyTogether() {
        AstJavaChunker chunker = chunker(2_000, 80);
        List<String> lines = """
                package demo;

                class OrderService {
                    @Deprecated
                    public String findOrder(
                            String userId,
                            long orderId) {
                        return userId + orderId;
                    }
                }
                """.lines().toList();

        AstJavaChunker.ChunkingResult result = chunker.chunk("demo/OrderService.java", "OrderService", lines);

        assertThat(result.parsed()).isTrue();
        assertThat(result.usedFallback()).isFalse();
        assertThat(result.declarations()).extracting(AstJavaChunker.Declaration::name)
                .contains("findOrder");
        assertThat(result.chunks()).anySatisfy(chunk -> {
            assertThat(chunk.content()).contains("@Deprecated", "findOrder", "return userId");
            assertThat(chunk.startLine()).isLessThanOrEqualTo(5);
            assertThat(chunk.endLine()).isGreaterThanOrEqualTo(9);
        });
    }

    @Test
    void repeatsDeclarationWhenLargeMethodNeedsSeveralChunks() {
        AstJavaChunker chunker = chunker(260, 5);
        List<String> lines = """
                package demo;
                class LargeService {
                    public void rebuildUserVector(String userId) {
                        step01();
                        step02();
                        step03();
                        step04();
                        step05();
                        step06();
                        step07();
                        step08();
                    }
                }
                """.lines().toList();

        AstJavaChunker.ChunkingResult result = chunker.chunk("demo/LargeService.java", "LargeService", lines);
        List<Chunker.Chunk> methodChunks = result.chunks().stream()
                .filter(c -> c.contextHeader().contains("AST") && c.endLine() >= 3 && c.startLine() <= 11)
                .toList();

        assertThat(methodChunks.size()).isGreaterThan(1);
        assertThat(methodChunks.get(1).content()).contains("declaration:", "rebuildUserVector");
        assertThat(result.chunks()).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(260));
    }

    @Test
    void fallsBackWithoutDroppingInvalidSource() {
        AstJavaChunker chunker = chunker(500, 20);
        List<String> lines = List.of("class Broken {", "  void x( {", "}");

        AstJavaChunker.ChunkingResult result = chunker.chunk("Broken.java", "Broken", lines);

        assertThat(result.parsed()).isFalse();
        assertThat(result.usedFallback()).isTrue();
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().getFirst().content()).contains("class Broken");
    }

    private static AstJavaChunker chunker(int maxChars, int maxLines) {
        RagProperties props = new RagProperties();
        props.getIndex().setMaxChars(maxChars);
        props.getIndex().setChunkLines(maxLines);
        return new AstJavaChunker(props);
    }
}
