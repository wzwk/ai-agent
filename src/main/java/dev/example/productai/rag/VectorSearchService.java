package dev.example.productai.rag;

import dev.example.productai.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

/**
 * 向量检索：query embedding → pgvector 余弦距离 top-K。
 * 小范围阶段顺序扫描（设计稿：<5 万切片够快）；全量后建 HNSW。
 *
 * @author wengzewei
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final JdbcTemplate jdbc;
    private final RagProperties props;
    private final EmbeddingClient embedding;

    public record Hit(String filePath, String contextHeader, int startLine, int endLine,
                      String snippet, double score) {}

    /**
     * @param query       自然语言查询（中文问题直接查——bge-m3 中英跨语言）
     * @param projectKeys 限定项目；空 = 全部已索引项目
     * @param topK        返回条数
     */
    public List<Hit> search(String query, List<String> projectKeys, int topK) {
        if (!props.isEnabled()) throw new IllegalStateException("RAG 未启用");
        float[] qv = embedding.embedOne(query);
        String vec = EmbeddingClient.toPgVector(qv);
        return jdbc.query(con -> {
                    PreparedStatement ps = con.prepareStatement("""
                            SELECT d.file_path, c.context_header, c.start_line, c.end_line,
                                   left(c.content, 700) AS snippet,
                                   1 - (c.embedding <=> ?::vector) AS score
                            FROM rag_chunks c
                            JOIN rag_documents d ON d.id = c.document_id
                            WHERE d.status = 'indexed' AND c.embedding IS NOT NULL
                              AND (?::varchar[] IS NULL OR c.project_key = ANY(?::varchar[]))
                            ORDER BY c.embedding <=> ?::vector
                            LIMIT ?
                            """);
                    ps.setString(1, vec);
                    if (projectKeys == null || projectKeys.isEmpty()) {
                        ps.setNull(2, java.sql.Types.ARRAY);
                        ps.setNull(3, java.sql.Types.ARRAY);
                    } else {
                        String[] arr = projectKeys.toArray(new String[0]);
                        java.sql.Array a = con.createArrayOf("varchar", arr);
                        ps.setArray(2, a);
                        ps.setArray(3, a);
                    }
                    ps.setString(4, vec);
                    ps.setInt(5, topK);
                    return ps;
                },
                (rs, i) -> new Hit(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getString(5), rs.getDouble(6)));
    }

    /** 各项目索引进度（管理接口用） */
    public List<Map<String, Object>> status() {
        return jdbc.queryForList("""
                SELECT project_key,
                       count(*) FILTER (WHERE status = 'indexed') AS files_indexed,
                       count(*) FILTER (WHERE status IN ('pending', 'indexing')) AS files_pending,
                       count(*) FILTER (WHERE status = 'failed') AS files_failed,
                       count(*) FILTER (WHERE status = 'deleted') AS files_deleted,
                       COALESCE(sum(chunk_count) FILTER (WHERE status = 'indexed'), 0) AS chunks,
                       to_char(max(indexed_at), 'MM-DD HH24:MI') AS last_indexed
                FROM rag_documents GROUP BY project_key ORDER BY project_key
                """);
    }

    /** 已建向量索引的项目 key（告知模型 semantic_search 的实际覆盖范围；失败返回空） */
    public List<String> indexedProjects() {
        try {
            return jdbc.queryForList(
                    "SELECT DISTINCT project_key FROM rag_documents WHERE status = 'indexed' ORDER BY project_key",
                    String.class);
        } catch (Exception e) {
            log.warn("查询已索引项目失败：{}", e.getMessage());
            return List.of();
        }
    }
}
