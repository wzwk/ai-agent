package dev.example.productai.agent;

import dev.example.productai.model.ProjectInfo;
import dev.example.productai.model.ChatMessage;
import dev.example.productai.model.ChatResponse;
import dev.example.productai.model.ConversationSession;
import dev.example.productai.search.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductAgentRoutingTest {

    @Test
    void serverControllerWinsOverFeignClientWithTheSameExactPath() {
        SearchResult feign = hit("sample-commodity-service",
                "D:/repo/infrastructure/feign/ItemRemoteClient.java", 100, 40);
        SearchResult controller = hit("sample-album-bff",
                "D:/repo/service/album/controller/ItemController.java", 100, 2);

        SearchResult selected = ProductAgent.selectEntryCandidate(List.of(feign, controller));

        assertEquals("sample-album-bff", selected.getProjectName());
    }

    @Test
    void clientIsNotUsedAsFallbackWhenAServiceRouteDefinitionExists() {
        SearchResult feign = hit("client-project",
                "D:/repo/api/RemoteClient.java", 100, 10);
        SearchResult route = hit("service-project",
                "D:/repo/api/PersonalEndpoint.java", 80, 1);

        SearchResult selected = ProductAgent.selectEntryCandidate(List.of(feign, route));

        assertEquals("service-project", selected.getProjectName());
    }

    @Test
    void explicitRequeryInstructionAlwaysBypassesAnswerCache() {
        assertTrue(ProductAgent.isCacheBypassRequest("这个不能命中缓存，要重新查"));
        assertTrue(ProductAgent.isCacheBypassRequest("绕过缓存重新检索当前代码"));
        assertFalse(ProductAgent.isCacheBypassRequest("这个接口是什么逻辑"));
    }

    @Test
    void fullProjectKeyInOrdinaryQuestionBecomesSearchScope() {
        List<ProjectInfo> catalog = List.of(project("sample-search"),
                project("sample-searching"), project("sample-commoditysearch"));

        assertEquals(List.of("sample-commoditysearch"), ProductAgent.mentionedProjects(
                "查 sample-commoditysearch 的文搜逻辑", catalog));
        assertEquals(List.of("sample-searching"), ProductAgent.mentionedProjects(
                "sample-searching 的搜索接口在哪里", catalog));
        assertEquals(List.of("sample-searching", "sample-commoditysearch"),
                ProductAgent.mentionedProjects("对比 sample-searching 和 sample-commoditysearch", catalog));
    }

    @Test
    void partialProjectKeyDoesNotSilentlyConstrainSearch() {
        List<ProjectInfo> catalog = List.of(project("sample-search"), project("sample-searching"));

        assertEquals(List.of(), ProductAgent.mentionedProjects("查 searching 的逻辑", catalog));
        assertEquals(List.of(), ProductAgent.mentionedProjects("查 sample-searching-v2 的逻辑", catalog));
    }

    @Test
    void bareBatchDeleteEndpointQuestionKeepsAnswerWithScopeNote() {
        AskContext question = AskContext.builder().question("批量删除的接口是哪个？").build();

        assertTrue(ProductAgent.needsBatchDeleteScopeNote(question, List.of(
                new ChatMessage("user", "小程序二维码的 LOGO 没有更新"))));
        assertTrue(ProductAgent.needsBatchDeleteScopeNote(AskContext.builder()
                .question("帮我找一下批量删除的接口在哪呀？").build(), List.of()));
        assertTrue(ProductAgent.needsBatchDeleteScopeNote(question,
                List.of(new ChatMessage("user", "批量删除接口是哪个"))));
        assertFalse(ProductAgent.needsBatchDeleteScopeNote(
                AskContext.builder().question("商品批量删除的接口是哪个？").build(), List.of()));
        assertFalse(ProductAgent.needsBatchDeleteScopeNote(
                AskContext.builder().question("批量删除接口有哪些？").build(), List.of()));

        ChatResponse answer = ChatResponse.builder()
                .answer("商品批量删除：POST /api/v1/items/batch/delete")
                .build();
        StringBuilder streamed = new StringBuilder();
        assertTrue(ProductAgent.qualifyBatchDeleteAnswer(answer, true,
                new AgentStreamHooks(null, streamed::append)));
        assertTrue(answer.getAnswer().contains("商品批量删除：POST /api/v1/items/batch/delete"));
        assertTrue(answer.getAnswer().contains("其他对象也有各自的批量删除接口"));
        assertEquals("\n\n这里按商品批量删除回答；其他对象也有各自的批量删除接口。",
                streamed.toString());
        assertFalse(ProductAgent.qualifyBatchDeleteAnswer(answer, true, null));
    }

    @Test
    void immediateBatchDeleteContextOrExplicitProjectKeepsNormalInvestigation() {
        AskContext question = AskContext.builder().question("批量删除的接口是哪个？").build();
        assertFalse(ProductAgent.needsBatchDeleteScopeNote(question,
                List.of(new ChatMessage("user", "商品批量删除后多久完成？"))));
        assertFalse(ProductAgent.needsBatchDeleteScopeNote(AskContext.builder()
                .question("批量删除的接口是哪个？")
                .specifiedProjects(List.of("sample-script-service")).build(), List.of()));
        ChatResponse answer = ChatResponse.builder()
                .answer("POST /api/v1/items/batch/delete").build();
        assertFalse(ProductAgent.qualifyBatchDeleteAnswer(answer, false, null));
        assertEquals("POST /api/v1/items/batch/delete", answer.getAnswer());

        ConversationSession session = new ConversationSession("test");
        session.append(new ChatMessage("user", "批量删除的接口是哪个？"));
        session.append(new ChatMessage("assistant", "原始答案"));
        session.replaceLastAssistantAnswer("原始答案", "原始答案（按商品）");
        assertEquals("原始答案（按商品）", session.snapshot().get(1).getContent());
    }

    @Test
    void changedBatchDeleteScenarioRechecksInsteadOfReusingCommodityCache() {
        List<ChatMessage> commodityHistory = List.of(
                new ChatMessage("user", "批量删除的接口是哪个？"),
                new ChatMessage("assistant", "商品批量删除：POST /api/v1/items/batch/delete"));
        assertTrue(ProductAgent.shouldRecheckBatchDeleteScenario(
                "话术批量删除的接口是哪个？", List.of()));
        assertTrue(ProductAgent.shouldRecheckBatchDeleteScenario("那话术呢？", commodityHistory));
        assertTrue(ProductAgent.shouldRecheckBatchDeleteScenario("其他场景呢？", commodityHistory));
        assertFalse(ProductAgent.shouldRecheckBatchDeleteScenario(
                "批量删除的接口是哪个？", commodityHistory));
        assertFalse(ProductAgent.shouldRecheckBatchDeleteScenario(
                "商品列表怎么排序？", commodityHistory));
    }

    private static ProjectInfo project(String name) {
        return ProjectInfo.builder().name(name).build();
    }

    private static SearchResult hit(String project, String path, int pathScore, int annotations) {
        return SearchResult.builder()
                .projectName(project)
                .filePath(path)
                .pathHit(pathScore == 100)
                .pathScore(pathScore)
                .routeAnnotationCount(annotations)
                .build();
    }
}
