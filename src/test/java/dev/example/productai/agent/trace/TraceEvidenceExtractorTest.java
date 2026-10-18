package dev.example.productai.agent.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceEvidenceExtractorTest {

    private final TraceEvidenceExtractor extractor = new TraceEvidenceExtractor();

    @Test
    void resolvesVisitedImplementationAndExtractsConcreteDataClients() {
        TraceState state = TraceState.start("s", "q", "user", null);
        state.putEdge(TraceEdge.builder()
                .fromSymbol("Rpc.bulkUpdate")
                .toSymbol("storage-service.IBatchCommodityOperateService.bulkUpdate")
                .status("pending").kind("local").build());

        String file = "sample-storage-service/src/BatchCommodityOperateService.java";
        String content = """
                88|batchTaskMapper.insert(entity);
                89|batchTaskRedisRepository.getExecTaskLock(taskId);
                90|commodityResourceMongoRepository.bulkUpdateCommodityFieldInHot(cmd);
                91|someFeignClient.callRemote();
                """;
        TraceEvidenceExtractor.Extraction result = extractor.collect(state, file, content);

        assertThat(result.resolvedEdges()).isEqualTo(1);
        assertThat(result.terminals()).isEqualTo(3);
        assertThat(state.pendingEdgeCount()).isZero();
        assertThat(state.getTerminals().values())
                .extracting(TraceTerminal::getType)
                .containsExactlyInAnyOrder("mysql", "redis", "mongo");
    }

    @Test
    void ignoresFeignAndCommentsAsTerminals() {
        TraceState state = TraceState.start("s", "q", "user", null);
        TraceEvidenceExtractor.Extraction result = extractor.collect(state, "p/A.java", """
                1|// redisTemplate.opsForValue().set(key, value);
                2|someFeignClient.query();
                """);

        assertThat(result.terminals()).isZero();
        assertThat(state.getTerminals()).isEmpty();
    }
}
