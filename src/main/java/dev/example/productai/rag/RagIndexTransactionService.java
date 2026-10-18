package dev.example.productai.rag;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * RAG 索引的数据库原子写入边界。
 *
 * <p>文件读取、切片和 embedding 都在事务外完成；这里只负责将一个文件的新版本一次性替换进数据库。
 * 任意一条切片写入失败时，文档元数据、旧切片删除和新切片插入会一起回滚。</p>
 */
@Service
public class RagIndexTransactionService {

    private final JdbcTemplate jdbc;

    public RagIndexTransactionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record IndexedChunk(int chunkIndex, Chunker.Chunk chunk, float[] vector) {}

    @Transactional
    public void replaceFile(String projectKey,
                            String fullPath,
                            String fileName,
                            String hash,
                            long sizeBytes,
                            int lineCount,
                            List<IndexedChunk> chunks) {
        jdbc.update("""
                        INSERT INTO rag_documents (project_key, doc_type, file_path, file_name, language,
                            file_hash, file_mtime, size_bytes, line_count, chunk_count, status, error, indexed_at)
                        VALUES (?, 'code', ?, ?, 'java', ?, now(), ?, ?, ?, 'indexing', NULL, now())
                        ON CONFLICT (project_key, file_path) DO UPDATE SET
                            file_name = EXCLUDED.file_name, file_hash = EXCLUDED.file_hash,
                            file_mtime = EXCLUDED.file_mtime, size_bytes = EXCLUDED.size_bytes,
                            line_count = EXCLUDED.line_count, chunk_count = EXCLUDED.chunk_count,
                            status = 'indexing', error = NULL, indexed_at = now(), updated_at = now()
                        """,
                projectKey, fullPath, fileName, hash, sizeBytes, lineCount, chunks.size());

        Long docId = jdbc.queryForObject(
                "SELECT id FROM rag_documents WHERE project_key = ? AND file_path = ?",
                Long.class, projectKey, fullPath);
        if (docId == null) {
            throw new IllegalStateException("索引文档写入后未找到: " + fullPath);
        }

        jdbc.update("DELETE FROM rag_chunks WHERE document_id = ?", docId);
        jdbc.batchUpdate("""
                        INSERT INTO rag_chunks (document_id, project_key, chunk_index, content,
                            context_header, start_line, end_line, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        IndexedChunk item = chunks.get(i);
                        Chunker.Chunk chunk = item.chunk();
                        ps.setLong(1, docId);
                        ps.setString(2, projectKey);
                        ps.setInt(3, item.chunkIndex());
                        ps.setString(4, chunk.content());
                        ps.setString(5, chunk.contextHeader());
                        ps.setInt(6, chunk.startLine());
                        ps.setInt(7, chunk.endLine());
                        ps.setString(8, EmbeddingClient.toPgVector(item.vector()));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
        jdbc.update("UPDATE rag_documents SET status = 'indexed', error = NULL, updated_at = now() WHERE id = ?", docId);
    }

    @Transactional
    public void markDeleted(String projectKey, String fullPath) {
        jdbc.update("""
                        UPDATE rag_documents SET status = 'deleted', updated_at = now()
                        WHERE project_key = ? AND file_path = ?
                        """, projectKey, fullPath);
        jdbc.update("""
                        DELETE FROM rag_chunks WHERE project_key = ? AND document_id IN
                        (SELECT id FROM rag_documents WHERE project_key = ? AND file_path = ?)
                        """, projectKey, projectKey, fullPath);
    }
}
