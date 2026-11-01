package dev.example.productai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.example.productai.agent.trace.TraceCompletenessGate;
import dev.example.productai.agent.trace.TraceContinuationPolicy;
import dev.example.productai.agent.trace.TraceEdge;
import dev.example.productai.agent.trace.TraceEvidenceExtractor;
import dev.example.productai.agent.trace.TraceState;
import dev.example.productai.agent.trace.TraceStatus;
import dev.example.productai.agent.trace.TraceStuckDetector;
import dev.example.productai.config.AgentProperties;
import dev.example.productai.llm.LlmClient;
import dev.example.productai.llm.ToolCall;
import dev.example.productai.llm.ToolChatMessage;
import dev.example.productai.model.ConversationSession;
import dev.example.productai.repository.TraceCheckpointRepository;
import dev.example.productai.search.JavaSymbolIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceGraphExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TraceGraphExecutor executor = new TraceGraphExecutor(
            mock(LlmClient.class), mock(AgentToolRuntime.class), new AgentProperties(),
            mock(TraceCheckpointRepository.class), new TraceCompletenessGate(), new TraceStuckDetector(),
            new TraceEvidenceExtractor(), new TraceContinuationPolicy(), mock(JavaSymbolIndex.class), mapper);

    @Test
    void updateTraceRejectsResolvedEvidenceAndTerminalFromUnreadFile() throws Exception {
        TraceState state = TraceState.start("s", "q", "dev", null);
        AgentHarvest harvest = new AgentHarvest();
        TraceGraphExecutor.LocalToolResult result = executor.updateTrace(mapper.readTree("""
                {
                  "edges":[{"from":"A.call","to":"B.save","status":"resolved","evidence_file":"p/B.java"}],
                  "terminals":[{"symbol":"mapper.insert","type":"mysql","evidence_file":"p/B.java","evidence_line":12}]
                }
                """), harvest, state);

        assertThat(result.ok()).isTrue();
        assertThat(result.content()).contains("尚未实读", "未接纳");
        assertThat(state.pendingEdgeCount()).isEqualTo(1);
        assertThat(state.getTerminals()).isEmpty();
    }

    @Test
    void updateTraceAcceptsEvidenceOnlyAfterFileWasRead() throws Exception {
        TraceState state = TraceState.start("s", "q", "dev", null);
        AgentHarvest harvest = new AgentHarvest();
        harvest.readFiles.add("p/B.java");

        executor.updateTrace(mapper.readTree("""
                {
                  "edges":[{"from":"A.call","to":"B.save","status":"resolved","evidence_file":"p/B.java","evidence_line":8}],
                  "terminals":[{"symbol":"mapper.insert","type":"mysql","evidence_file":"p/B.java","evidence_line":12}]
                }
                """), harvest, state);

        assertThat(state.pendingEdgeCount()).isZero();
        assertThat(state.getTerminals()).hasSize(1);
    }

    @Test
    void replayStoredEvidenceResolvesPendingEdgeAndRestoresTerminal() {
        TraceState state = TraceState.start("s", "q", "dev", null);
        state.putEdge(TraceEdge.builder().fromSymbol("Controller.call")
                .toSymbol("BatchService.save").status("pending").kind("local").build());
        AgentHarvest harvest = new AgentHarvest();
        harvest.materials.add("【read_file p/BatchService.java 1-30】\n12|batchTaskMapper.insert(entity);");

        executor.replayStoredEvidence(state, harvest);

        assertThat(state.pendingEdgeCount()).isZero();
        assertThat(state.getTerminals().values()).extracting("type").containsExactly("mysql");
    }

    @Test
    void closureCommitReplaysEvidenceAddedBeforeThePendingEdgeAndFinishesWithoutLlm() {
        TraceState state = TraceState.start("s", "q", "dev", null);
        AgentHarvest harvest = new AgentHarvest();
        harvest.materials.add("【read_file p/BatchService.java 1-30】\n12|batchTaskMapper.insert(entity);");
        state.putEdge(TraceEdge.builder().fromSymbol("Controller.call")
                .toSymbol("BatchService.save").status("pending").kind("local").build());

        boolean complete = executor.reconcileClosureFromReadEvidence(state, harvest);

        assertThat(complete).isTrue();
        assertThat(state.isFinishRequested()).isTrue();
        assertThat(state.pendingEdgeCount()).isZero();
        assertThat(state.getTerminals()).isNotEmpty();
    }

    @Test
    void traceDecisionCompactionKeepsFreshStateAndOnlyRecentCompleteToolGroups() {
        List<ToolChatMessage> messages = new ArrayList<>();
        messages.add(ToolChatMessage.system("old-system"));
        messages.add(ToolChatMessage.user("old-planner"));
        for (int i = 0; i < 8; i++) {
            ToolCall call = new ToolCall("call-" + i, "read_file", "{\"payload\":\"" + "x".repeat(9000) + "\"}");
            messages.add(ToolChatMessage.builder().role("assistant").content("").toolCalls(List.of(call)).build());
            messages.add(ToolChatMessage.tool(call.id(), "y".repeat(9000)));
        }
        TraceState state = TraceState.start("s", "q", "dev", List.of("sample-album-bff"));

        executor.compactDecisionContext(messages, "fresh-system", state,
                new ConversationSession("s"), AskContext.builder().question("q").mode("dev").build());

        assertThat(messages.get(0).getRole()).isEqualTo("system");
        assertThat(messages.get(0).getContent()).contains("fresh-system", "状态图控制协议");
        assertThat(messages.get(1).getContent()).contains("当前状态图", state.getTraceId());
        assertThat(messages.get(2).getRole()).isEqualTo("assistant");
        assertThat(messages.stream().mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length()).sum())
                .isLessThan(70_000);
    }

    @Test
    void resumeResetsStuckBudgetButKeepsAccumulatedGraph() {
        TraceState state = TraceState.start("s", "原始问题", "dev", null);
        state.setNoProgressRounds(3);
        state.setIncompleteReason("连续三轮没有新增证据");
        state.setFinishRequested(true);
        state.setStatus(TraceStatus.PARTIAL);
        state.putEdge(TraceEdge.builder().fromSymbol("A").toSymbol("B")
                .status("pending").kind("local").build());

        executor.prepareForResume(state, "继续查", List.of("content-service"));

        assertThat(state.getNoProgressRounds()).isZero();
        assertThat(state.getIncompleteReason()).isNull();
        assertThat(state.isFinishRequested()).isFalse();
        assertThat(state.getStatus()).isEqualTo(TraceStatus.RUNNING);
        assertThat(state.pendingEdgeCount()).isEqualTo(1);
        assertThat(state.getTargetProjects()).contains("content-service");
    }

    @Test
    void finalMaterialsKeepsEarlyMainChainEvidenceBeforeRecentTailFiles() {
        AgentToolRuntime runtime = mock(AgentToolRuntime.class);
        TraceGraphExecutor localExecutor = new TraceGraphExecutor(
                mock(LlmClient.class), runtime, new AgentProperties(),
                mock(TraceCheckpointRepository.class), new TraceCompletenessGate(), new TraceStuckDetector(),
                new TraceEvidenceExtractor(), new TraceContinuationPolicy(), mock(JavaSymbolIndex.class), mapper);
        String controller = "【read_file sample-album-bff/ItemController.java 370-460】\n"
                + "personalAllCommon();\n" + "c".repeat(7000);
        String service = "【read_file sample-album-bff/ItemService.java 1-160】\nfindItems();";
        String tail = "【read_file search/LastPostprocessor.java 1-100】\npostprocess();";
        when(runtime.distillMaterials(any())).thenReturn(List.of(controller, service, tail));

        TraceState state = TraceState.start("s", "/personal/all", "dev", null);
        state.putEdge(TraceEdge.builder().fromSymbol("ItemController.personalAll")
                .toSymbol("ItemService.findCommodities")
                .status("resolved").kind("local")
                .evidenceFile("sample-album-bff/ItemController.java").evidenceLine(378).build());

        String result = localExecutor.finalMaterials(new AgentHarvest(), state, "/personal/all", 9_000);

        assertThat(result).contains("ItemController.java", "LastPostprocessor.java");
        assertThat(result.indexOf("ItemController.java"))
                .isLessThan(result.indexOf("LastPostprocessor.java"));
        assertThat(result.length()).isLessThanOrEqualTo(9_000);
    }

    @Test
    void completeAnswerCannotContainGapClaims() {
        assertThat(TraceGraphExecutor.answerHasGapClaim("链路完整，所有分支均已验证")).isFalse();
        assertThat(TraceGraphExecutor.answerHasGapClaim("具体 DB 实现该环节未检索到")).isTrue();
        assertThat(TraceGraphExecutor.answerHasGapClaim("还有一个待确认分支")).isTrue();
    }

    @Test
    void closureClearsOnlyUnreadReasonsWhoseNamedTypeWasActuallyRead() {
        TraceState state = TraceState.start("s", "q", "dev", null);
        state.getUnresolvedReasons().add("TextSearchService.java 未在材料中，分发逻辑未确认");
        state.getUnresolvedReasons().add("动态配置值无法从源码确认");
        AgentHarvest harvest = new AgentHarvest();
        harvest.readFiles.add("commoditysearch/TextSearchService.java");

        int cleared = executor.clearSatisfiedUnresolvedReasons(state, harvest);

        assertThat(cleared).isEqualTo(1);
        assertThat(state.getUnresolvedReasons()).containsExactly("动态配置值无法从源码确认");
    }
}
