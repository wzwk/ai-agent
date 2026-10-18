package dev.example.productai.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.example.productai.model.ChatMessage;
import dev.example.productai.model.ChatResponse;
import dev.example.productai.model.ConversationSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段一持久化：会话 / 消息 / 智能体轨迹落 PostgreSQL。
 *
 * 设计要点：
 * - 所有公开方法失败只 warn 不抛——PG 没起、表没建、网络断，问答功能照常（降级内存）；
 * - DB 挂掉后 5 分钟内不再重试，避免每问一次都白等连接超时；
 * - 落库时机在 ProductAgent.ask 外层包装，两引擎（agent/workflow）统一覆盖。
 *
 * @author wengzewei
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistenceService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 上次 DB 失败时间戳；0 = 一直可用 */
    private volatile long lastFailEpoch = 0;
    private static final long RETRY_BACKOFF_MS = 5 * 60_000L;

    /** DB 是否值得尝试（失败后进入冷却） */
    private boolean shouldTry() {
        return System.currentTimeMillis() - lastFailEpoch > RETRY_BACKOFF_MS;
    }

    private void fail(String action, Exception e) {
        lastFailEpoch = System.currentTimeMillis();
        log.warn("持久化失败（{}），降级内存模式，5 分钟后重试: {}", action, e.getMessage());
    }

    // ==================================================================================
    // 写入
    // ==================================================================================

    /**
     * 一轮问答落库：会话 upsert + user/assistant 两行消息 + 智能体轨迹。
     * needKey（额度引导）不算真实轮次，跳过。
     */
    public void saveTurn(ConversationSession session, String mode, String question, ChatResponse resp) {
        if (resp == null || Boolean.TRUE.equals(resp.getNeedKey())) return;
        if (!shouldTry()) return;
        try {
            String sid = session.getSessionId();
            // 会话 upsert：title 只在首次写入
            jdbc.update("""
                    INSERT INTO conversations (id, mode, title, asked_count, message_count, last_active_at)
                    VALUES (?, ?, ?, ?, 0, now())
                    ON CONFLICT (id) DO UPDATE SET
                        mode = EXCLUDED.mode,
                        last_active_at = now(),
                        asked_count = GREATEST(conversations.asked_count, EXCLUDED.asked_count)
                    """,
                    sid, mode, truncate(question, 100), askedCountOf(session));

            jdbc.update("INSERT INTO messages (conversation_id, role, content) VALUES (?, 'user', ?)",
                    sid, question);

            // assistant 行带富字段；用 PG 原生 RETURNING id 拿自增主键（KeyHolder 在部分驱动下取不到）
            Long msgId = jdbc.queryForObject(
                    """
                    INSERT INTO messages
                      (conversation_id, role, content, confidence, need_human, citations, extra, duration_ms)
                    VALUES (?, 'assistant', ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                    RETURNING id
                    """,
                    Long.class,
                    sid, resp.getAnswer(), confNum(resp.getConfidence()), resp.isNeedHuman(),
                    srcArray(resp.getSourceFiles()), extraJson(resp), (int) resp.getElapsedMs());

            // 智能体轨迹（workflow 引擎没有 steps，自然为空）
            if (msgId != null && resp.getSteps() != null) {
                for (ChatResponse.Step st : resp.getSteps()) {
                    jdbc.update("""
                            INSERT INTO agent_steps
                              (message_id, step_no, phase, tool_name, tool_args, result_summary, elapsed_ms, status)
                            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                            """,
                            msgId.longValue(), st.getNo(), st.getPhase(), st.getTool(),
                            jsonOrEmptyObject(st.getArgs()),
                            st.getSummary(), st.getElapsedMs(),
                            st.getStatus() == null ? "ok" : st.getStatus());
                }
            }
            jdbc.update("UPDATE conversations SET message_count = message_count + 2 WHERE id = ?", sid);
        } catch (Exception e) {
            fail("saveTurn " + session.getSessionId(), e);
        }
    }

    private int askedCountOf(ConversationSession s) {
        int c = s.getAskedCount();
        return Math.max(c, 0);
    }

    // ==================================================================================
    // 恢复（进程重启 / 30 分钟 TTL 清理后，从 DB 重建内存会话）
    // ==================================================================================

    /** 会话在内存里不存在时，从 DB 恢复最近若干条消息与额度计数；幂等（已有内容则跳过） */
    public void restoreIfAbsent(ConversationSession session) {
        if (session.getAskedCount() > 0 || !session.snapshot().isEmpty()) return;
        if (!shouldTry()) return;
        try {
            List<ChatMessage> msgs = jdbc.query(
                    "SELECT role, content FROM (SELECT id, role, content FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 12) t ORDER BY id ASC",
                    (rs, i) -> new ChatMessage(rs.getString(1), rs.getString(2)),
                    session.getSessionId());
            // 首问时会话尚未入库，查不到行是正常情况（不算 DB 故障，不能触发冷却）
            List<Integer> asked = jdbc.query(
                    "SELECT asked_count FROM conversations WHERE id = ?",
                    (rs, i) -> rs.getInt(1), session.getSessionId());
            int askedCount = asked.isEmpty() ? 0 : asked.get(0);
            if (msgs != null && !msgs.isEmpty()) {
                session.restoreFrom(msgs, askedCount);
                log.info("会话从 DB 恢复: sessionId={}, 消息={} 条, asked={}",
                        session.getSessionId(), msgs.size(), askedCount);
            }
        } catch (Exception e) {
            fail("restore " + session.getSessionId(), e);
        }
    }

    // ==================================================================================
    // 查询（前端历史回放 / 会话列表 / 软删）
    // ==================================================================================

    /** 最近会话列表（active 状态） */
    public List<Map<String, Object>> listConversations(int limit) {
        if (!shouldTry()) return List.of();
        try {
            return jdbc.queryForList("""
                    SELECT id, mode, COALESCE(title, '(无标题)') AS title, message_count,
                           to_char(last_active_at, 'MM-DD HH24:MI') AS last_active
                    FROM conversations WHERE status = 'active'
                    ORDER BY last_active_at DESC LIMIT ?
                    """, limit);
        } catch (Exception e) {
            fail("listConversations", e);
            return List.of();
        }
    }

    /** 会话全部消息（含 assistant 的轨迹），供前端回放 */
    public List<Map<String, Object>> findMessages(String sessionId) {
        if (!shouldTry()) return List.of();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, role, content, confidence, need_human, citations, extra, duration_ms
                    FROM messages WHERE conversation_id = ? ORDER BY id
                    """, sessionId);
            Map<Long, List<Map<String, Object>>> stepsByMsg = new LinkedHashMap<>();
            jdbc.query("""
                            SELECT s.message_id, s.step_no, s.phase, s.tool_name, s.tool_args, s.result_summary, s.elapsed_ms, s.status
                            FROM agent_steps s JOIN messages m ON m.id = s.message_id
                            WHERE m.conversation_id = ? ORDER BY s.id
                            """,
                    rs -> {
                        Map<String, Object> st = new LinkedHashMap<>();
                        st.put("no", rs.getInt("step_no"));
                        st.put("phase", rs.getString("phase"));
                        st.put("tool", rs.getString("tool_name"));
                        st.put("args", rs.getString("tool_args"));
                        st.put("summary", rs.getString("result_summary"));
                        st.put("elapsedMs", rs.getInt("elapsed_ms"));
                        st.put("status", rs.getString("status"));
                        stepsByMsg.computeIfAbsent(rs.getLong("message_id"), k -> new java.util.ArrayList<>()).add(st);
                    },
                    sessionId);

            for (Map<String, Object> row : rows) {
                if (!"assistant".equals(row.get("role"))) continue;
                long mid = ((Number) row.get("id")).longValue();
                row.put("steps", stepsByMsg.getOrDefault(mid, List.of()));
                JsonNode extra = parseJson(String.valueOf(row.get("extra")));
                row.put("engine", extra.path("engine").asText(null));
                row.put("stepsUsed", extra.path("stepsUsed").asInt(0));
                row.put("mode", extra.path("mode").asText(null));
                row.put("completionStatus", extra.path("completionStatus").asText(null));
                row.put("incompleteReason", extra.path("incompleteReason").asText(null));
                row.put("traceId", extra.path("traceId").asText(null));
                if (extra.has("pendingEdges")) row.put("pendingEdges", extra.path("pendingEdges").asInt());
                if (extra.has("terminalCount")) row.put("terminalCount", extra.path("terminalCount").asInt());
                if (extra.has("performance") && extra.path("performance").isObject()) {
                    row.put("performance", mapper.convertValue(extra.path("performance"), Map.class));
                }
                String confText = extra.path("confidence").asText(null);
                if (confText != null) row.put("confidence", confText);   // 字符串版优先（前端 chip 用）
                row.put("sourceFiles", parseJson(String.valueOf(row.get("citations"))));
                row.put("elapsedMs", row.get("duration_ms") == null ? 0
                        : ((Number) row.get("duration_ms")).intValue());
                row.remove("extra");
                row.remove("citations");
                row.remove("duration_ms");
                row.remove("id");
            }
            // user 行也去掉 id
            rows.forEach(r -> r.remove("id"));
            return rows;
        } catch (Exception e) {
            fail("findMessages " + sessionId, e);
            return List.of();
        }
    }

    /** 软删会话（status=deleted，数据保留） */
    public boolean softDelete(String sessionId) {
        if (!shouldTry()) return false;
        try {
            int n = jdbc.update("UPDATE conversations SET status = 'deleted' WHERE id = ?", sessionId);
            return n > 0;
        } catch (Exception e) {
            fail("softDelete " + sessionId, e);
            return false;
        }
    }

    /** 探活：DB 可用返回 true（前端 health 展示持久化状态用） */
    public boolean ping() {
        if (!shouldTry()) return false;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** high=1.0 / medium=0.6 / low=0.2（NUMERIC(4,3) 列；原始字符串在 extra 里） */
    private java.math.BigDecimal confNum(String confidence) {
        if (confidence == null) return null;
        return switch (confidence.toLowerCase()) {
            case "high" -> new java.math.BigDecimal("1.000");
            case "medium" -> new java.math.BigDecimal("0.600");
            case "low" -> new java.math.BigDecimal("0.200");
            default -> null;
        };
    }

    private String srcArray(List<String> files) {
        ArrayNode arr = mapper.createArrayNode();
        if (files != null) files.forEach(arr::add);
        return arr.toString();
    }

    /** agent_steps.tool_args 是 jsonb；缓存回放的旧步骤可能是空串，必须规范成合法 JSON。 */
    private String jsonOrEmptyObject(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        try {
            mapper.readTree(raw);
            return raw;
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String extraJson(ChatResponse r) {
        ObjectNode o = mapper.createObjectNode();
        if (r.getEngine() != null) o.put("engine", r.getEngine());
        if (r.getStepsUsed() != null) o.put("stepsUsed", r.getStepsUsed());
        if (r.getConfidence() != null) o.put("confidence", r.getConfidence());
        if (r.getMode() != null) o.put("mode", r.getMode());
        if (r.getCompletionStatus() != null) o.put("completionStatus", r.getCompletionStatus());
        if (r.getIncompleteReason() != null) o.put("incompleteReason", r.getIncompleteReason());
        if (r.getTraceId() != null) o.put("traceId", r.getTraceId());
        if (r.getPendingEdges() != null) o.put("pendingEdges", r.getPendingEdges());
        if (r.getTerminalCount() != null) o.put("terminalCount", r.getTerminalCount());
        if (r.getPerformance() != null) o.set("performance", mapper.valueToTree(r.getPerformance()));
        return o.toString();
    }

    private JsonNode parseJson(String raw) {
        try {
            return raw == null || "null".equals(raw) ? mapper.nullNode() : mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.nullNode();
        }
    }
}
