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
                .toSymbol("core-service.IBatchItemService.bulkUpdate")
                .status("pending").kind("local").build());

        String file = "sample-core-service/src/BatchItemService.java";
        String content = """
                88|batchTaskMapper.insert(entity);
                89|batchTaskRedisRepository.getExecTaskLock(taskId);
                90|itemMongoRepository.bulkUpdateCommodityFieldInHot(cmd);
                91|someFeignClient.callRemote();
                """;
        TraceEvidenceExtractor.Extraction result = extractor.collect(state, file, content);

        assertThat(result.resolvedEdges()).isEqualTo(1);
        assertThat(result.terminals()).isEqualTo(3);
        assertThat(state.pendingEdgeCount()).isEqualTo(1);
        assertThat(state.getEdges().values()).anySatisfy(edge -> {
            assertThat(edge.getToSymbol()).isEqualTo("someFeignClient.callRemote");
            assertThat(edge.getKind()).isEqualTo("feign");
        });
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
        assertThat(state.pendingEdgeCount()).isEqualTo(1);
    }

    @Test
    void readingTheOutboundClientFileResolvesTheAutomaticallyDiscoveredEdge() {
        TraceState state = TraceState.start("s", "q", "user", null);
        extractor.collect(state, "p/Caller.java", "12|itemQueryFeignClient.queryProductProps(dto);");

        TraceEvidenceExtractor.Extraction result = extractor.collect(state,
                "content-service/ItemQueryFeignClient.java", "20|@RequestMapping(\"/query\")");

        assertThat(result.resolvedEdges()).isEqualTo(1);
        assertThat(state.pendingEdgeCount()).isZero();
    }

    @Test
    void resolvesSpecificPendingMethodWhenProviderControllerWasRead() {
        TraceState state = TraceState.start("s", "q", "user", null);
        state.putEdge(TraceEdge.builder()
                .fromSymbol("SampleAlbumComponent.queryItemsByImage")
                .toSymbol("sample-album-bff queryItemsByImage 端点")
                .status("pending").kind("feign")
                .evidenceFile("commoditysearch/SampleAlbumComponent.java").build());

        TraceEvidenceExtractor.Extraction result = extractor.collect(state,
                "sample-album-bff/SampleSearchController.java", """
                        119|@Override
                        120|@NoneAuth
                        121|public ResultBeanExt queryItemsByImage(SearchPersonalImgUrlsDTO dto) {
                        """);

        assertThat(result.resolvedEdges()).isEqualTo(1);
        assertThat(state.pendingEdgeCount()).isZero();
        assertThat(state.getEdges().values().iterator().next().getEvidenceFile())
                .isEqualTo("sample-album-bff/SampleSearchController.java");
    }

    @Test
    void recognizesAnnotationDrivenRocketMqPublisherAsTerminal() {
        TraceState state = TraceState.start("s", "q", "user", null);

        TraceEvidenceExtractor.Extraction result = extractor.collect(state,
                "increase/UserStageFinishProduceService.java", """
                        20|@SampleSendMessage(topic = "${topic}", messageType = SampleMessageTypeEnum.DELAYED)
                        21|public SampleMessage<String> send(SampleEvent dto) {
                        """);

        assertThat(result.terminals()).isEqualTo(1);
        assertThat(state.getTerminals().values()).extracting(TraceTerminal::getType).containsExactly("mq");
    }
}
