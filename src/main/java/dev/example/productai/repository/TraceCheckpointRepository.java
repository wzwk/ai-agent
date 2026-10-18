package dev.example.productai.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.productai.agent.trace.TraceEdge;
import dev.example.productai.agent.trace.TraceState;
import dev.example.productai.agent.trace.TraceTerminal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TraceState 的 PostgreSQL checkpoint；数据库不可用时退化为进程内保存，不影响问答。
 * state_json 是权威快照，nodes/edges 是便于观察与面试演示的物化视图。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TraceCheckpointRepository {

    private static final long RETRY_COOLDOWN_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);
    private final Map<String, TraceState> memoryBySession = new ConcurrentHashMap<>();
    private volatile long retryAfterEpochMs;

    public void save(TraceState state, String eventType) {
        state.normalize();
        state.touch();
        memoryBySession.put(state.getSessionId(), state);
        if (!ensureSchema()) return;
        try {
            String json = mapper.writeValueAsString(state);
            jdbc.update("""
                    INSERT INTO trace_runs
                      (trace_id, session_id, original_question, latest_question, mode, status,
                       decision_rounds, source_state_json, incomplete_reason, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (trace_id) DO UPDATE SET
                      latest_question=EXCLUDED.latest_question,
                      status=EXCLUDED.status,
                      decision_rounds=EXCLUDED.decision_rounds,
                      source_state_json=EXCLUDED.source_state_json,
                      incomplete_reason=EXCLUDED.incomplete_reason,
                      updated_at=EXCLUDED.updated_at
                    """,
                    state.getTraceId(), state.getSessionId(), state.getOriginalQuestion(),
                    state.getLatestQuestion(), state.getMode(), state.getStatus().name(),
                    state.getDecisionRounds(), json, state.getIncompleteReason(),
                    new Timestamp(state.getCreatedAtEpochMs()), new Timestamp(state.getUpdatedAtEpochMs()));
            jdbc.update("""
                    INSERT INTO trace_checkpoints(trace_id, decision_round, event_type, state_json)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, state.getTraceId(), state.getDecisionRounds(), eventType, json);
            refreshGraphViews(state);
        } catch (Exception e) {
            onDatabaseFailure("保存调用链 checkpoint", e);
        }
    }

    public TraceState loadLatestResumable(String sessionId) {
        TraceState memory = memoryBySession.get(sessionId);
        if (memory != null && isResumable(memory)) return memory;
        if (!ensureSchema()) return null;
        try {
            List<String> rows = jdbc.query("""
                    SELECT source_state_json::text
                    FROM trace_runs
                    WHERE session_id = ? AND status IN ('RUNNING', 'PARTIAL')
                    ORDER BY updated_at DESC
                    LIMIT 1
                    """, (rs, rowNum) -> rs.getString(1), sessionId);
            if (rows.isEmpty()) return null;
            TraceState state = mapper.readValue(rows.getFirst(), TraceState.class);
            state.normalize();
            memoryBySession.put(sessionId, state);
            return state;
        } catch (Exception e) {
            onDatabaseFailure("读取调用链 checkpoint", e);
            return memory;
        }
    }

    private boolean isResumable(TraceState state) {
        return state.getStatus() != null
                && (state.getStatus().name().equals("RUNNING") || state.getStatus().name().equals("PARTIAL"));
    }

    private void refreshGraphViews(TraceState state) {
        try {
            jdbc.update("DELETE FROM trace_nodes WHERE trace_id = ?", state.getTraceId());
            for (String file : state.getReadFiles()) {
                jdbc.update("""
                        INSERT INTO trace_nodes(trace_id, node_key, node_type, evidence_file)
                        VALUES (?, ?, 'FILE', ?) ON CONFLICT DO NOTHING
                        """, state.getTraceId(), "file:" + file, file);
            }
            for (TraceTerminal terminal : state.getTerminals().values()) {
                jdbc.update("""
                        INSERT INTO trace_nodes
                          (trace_id, node_key, node_type, evidence_file, evidence_line, detail)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """, state.getTraceId(), "terminal:" + terminal.identity(),
                        terminal.getType(), terminal.getEvidenceFile(), terminal.getEvidenceLine(), terminal.getDetail());
            }

            jdbc.update("DELETE FROM trace_edges WHERE trace_id = ?", state.getTraceId());
            for (TraceEdge edge : state.getEdges().values()) {
                jdbc.update("""
                        INSERT INTO trace_edges
                          (trace_id, edge_key, from_symbol, to_symbol, edge_type, status,
                           evidence_file, evidence_line, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """, state.getTraceId(), edge.identity(), edge.getFromSymbol(), edge.getToSymbol(),
                        edge.getKind(), edge.getStatus(), edge.getEvidenceFile(), edge.getEvidenceLine(), edge.getReason());
            }
        } catch (Exception e) {
            // 权威 state_json 已保存；物化视图失败不影响恢复能力。
            log.debug("刷新调用链物化视图失败 traceId={}: {}", state.getTraceId(), e.getMessage());
        }
    }

    private boolean ensureSchema() {
        if (schemaReady.get()) return true;
        long now = Instant.now().toEpochMilli();
        if (now < retryAfterEpochMs) return false;
        synchronized (schemaReady) {
            if (schemaReady.get()) return true;
            try {
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS trace_runs (
                          trace_id VARCHAR(32) PRIMARY KEY,
                          session_id VARCHAR(64) NOT NULL,
                          original_question TEXT NOT NULL,
                          latest_question TEXT,
                          mode VARCHAR(16),
                          status VARCHAR(24) NOT NULL,
                          decision_rounds INTEGER NOT NULL DEFAULT 0,
                          source_state_json JSONB NOT NULL,
                          incomplete_reason TEXT,
                          created_at TIMESTAMP NOT NULL DEFAULT now(),
                          updated_at TIMESTAMP NOT NULL DEFAULT now()
                        )
                        """);
                jdbc.execute("CREATE INDEX IF NOT EXISTS idx_trace_runs_session_updated ON trace_runs(session_id, updated_at DESC)");
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS trace_checkpoints (
                          id BIGSERIAL PRIMARY KEY,
                          trace_id VARCHAR(32) NOT NULL REFERENCES trace_runs(trace_id) ON DELETE CASCADE,
                          decision_round INTEGER NOT NULL,
                          event_type VARCHAR(32) NOT NULL,
                          state_json JSONB NOT NULL,
                          created_at TIMESTAMP NOT NULL DEFAULT now()
                        )
                        """);
                jdbc.execute("CREATE INDEX IF NOT EXISTS idx_trace_checkpoints_trace ON trace_checkpoints(trace_id, id)");
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS trace_nodes (
                          trace_id VARCHAR(32) NOT NULL REFERENCES trace_runs(trace_id) ON DELETE CASCADE,
                          node_key TEXT NOT NULL,
                          node_type VARCHAR(32),
                          evidence_file TEXT,
                          evidence_line INTEGER,
                          detail TEXT,
                          PRIMARY KEY(trace_id, node_key)
                        )
                        """);
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS trace_edges (
                          trace_id VARCHAR(32) NOT NULL REFERENCES trace_runs(trace_id) ON DELETE CASCADE,
                          edge_key TEXT NOT NULL,
                          from_symbol TEXT,
                          to_symbol TEXT,
                          edge_type VARCHAR(32),
                          status VARCHAR(24),
                          evidence_file TEXT,
                          evidence_line INTEGER,
                          reason TEXT,
                          PRIMARY KEY(trace_id, edge_key)
                        )
                        """);
                schemaReady.set(true);
                log.info("调用链 checkpoint 表已就绪");
                return true;
            } catch (Exception e) {
                onDatabaseFailure("初始化调用链 checkpoint 表", e);
                return false;
            }
        }
    }

    private void onDatabaseFailure(String action, Exception e) {
        retryAfterEpochMs = Instant.now().toEpochMilli() + RETRY_COOLDOWN_MS;
        schemaReady.set(false);
        log.warn("{}失败，暂时降级为进程内状态: {}", action, rootMessage(e));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }
}
