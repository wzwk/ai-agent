package dev.example.productai.agent.trace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 可持久化的调用链调查状态。它记录事实图和待办，不保存 LLM 的瞬时“想法”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceState {
    private String traceId;
    private String sessionId;
    private String originalQuestion;
    private String latestQuestion;
    private String mode;
    private TraceStatus status;
    private boolean finishRequested;
    private int decisionRounds;
    private int noProgressRounds;
    private long createdAtEpochMs;
    private long updatedAtEpochMs;
    private String incompleteReason;

    @Builder.Default
    private Set<String> targetProjects = new LinkedHashSet<>();
    @Builder.Default
    private Set<String> searchedProjects = new LinkedHashSet<>();
    @Builder.Default
    private Set<String> readFiles = new LinkedHashSet<>();
    @Builder.Default
    private Set<String> executedToolSignatures = new LinkedHashSet<>();
    @Builder.Default
    private List<String> materials = new ArrayList<>();
    @Builder.Default
    private Map<String, TraceEdge> edges = new LinkedHashMap<>();
    @Builder.Default
    private Map<String, TraceTerminal> terminals = new LinkedHashMap<>();
    @Builder.Default
    private List<String> unresolvedReasons = new ArrayList<>();

    public static TraceState start(String sessionId, String question, String mode, List<String> projects) {
        long now = Instant.now().toEpochMilli();
        TraceState state = TraceState.builder()
                .traceId(UUID.randomUUID().toString().replace("-", ""))
                .sessionId(sessionId)
                .originalQuestion(question)
                .latestQuestion(question)
                .mode(mode)
                .status(TraceStatus.RUNNING)
                .createdAtEpochMs(now)
                .updatedAtEpochMs(now)
                .build();
        if (projects != null) state.targetProjects.addAll(projects);
        return state;
    }

    /** 兼容旧 checkpoint 或手工构造对象缺少集合字段的情况。 */
    public void normalize() {
        if (targetProjects == null) targetProjects = new LinkedHashSet<>();
        if (searchedProjects == null) searchedProjects = new LinkedHashSet<>();
        if (readFiles == null) readFiles = new LinkedHashSet<>();
        if (executedToolSignatures == null) executedToolSignatures = new LinkedHashSet<>();
        if (materials == null) materials = new ArrayList<>();
        if (edges == null) edges = new LinkedHashMap<>();
        if (terminals == null) terminals = new LinkedHashMap<>();
        if (unresolvedReasons == null) unresolvedReasons = new ArrayList<>();
        if (status == null) status = TraceStatus.RUNNING;
    }

    public int pendingEdgeCount() {
        return (int) edges.values().stream().filter(TraceEdge::isPending).count();
    }

    public void putEdge(TraceEdge edge) {
        if (edge == null || edge.getToSymbol() == null || edge.getToSymbol().isBlank()) return;
        edges.put(edge.identity(), edge);
    }

    public void putTerminal(TraceTerminal terminal) {
        if (terminal == null || terminal.getSymbol() == null || terminal.getSymbol().isBlank()) return;
        terminals.put(terminal.identity(), terminal);
    }

    public int progressScore() {
        return searchedProjects.size() + readFiles.size() + edges.size() * 2
                + terminals.size() * 3 + materials.size();
    }

    public void touch() {
        updatedAtEpochMs = Instant.now().toEpochMilli();
    }
}
