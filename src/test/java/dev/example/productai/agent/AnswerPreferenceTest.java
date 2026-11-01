package dev.example.productai.agent;

import dev.example.productai.config.AgentProperties;
import dev.example.productai.config.ProjectsConfigLoader;
import dev.example.productai.config.ProjectsProperties;
import dev.example.productai.config.SearchProperties;
import dev.example.productai.llm.LlmClient;
import dev.example.productai.rag.VectorSearchService;
import dev.example.productai.search.CodeSearcher;
import dev.example.productai.search.MarkdownDocumentIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AnswerPreferenceTest {

    @Test
    void businessModeDefaultsToBriefWithoutRequiringMagicWords() {
        AskContext context = AskContext.builder()
                .question("团队 token 失效怎么处理")
                .mode("user")
                .build();

        assertTrue(context.usesBriefAnswer());
        assertFalse(context.prefersConciseAnswer());
        assertTrue("u:b:v2".equals(context.cacheVariant("user")));
    }

    @Test
    void businessModeCanExplicitlyRequestImplementationDetails() {
        AskContext context = AskContext.builder()
                .question("详细讲一下这个接口的完整调用链和具体实现")
                .mode("user")
                .build();

        assertTrue(context.prefersDetailedAnswer());
        assertFalse(context.usesBriefAnswer());
        assertTrue("u:d:v2".equals(context.cacheVariant("user")));
    }

    @Test
    void developerModeRemainsDetailedUnlessConciseIsExplicit() {
        AskContext detailed = AskContext.builder().question("分析这个接口").mode("dev").build();
        AskContext brief = AskContext.builder().question("只给结论").mode("dev").build();

        assertFalse(detailed.usesBriefAnswer());
        assertTrue(brief.usesBriefAnswer());
        assertTrue("d:d:v2".equals(detailed.cacheVariant("dev")));
        assertTrue("d:b:v2".equals(brief.cacheVariant("dev")));
    }

    @Test
    void explicitNoDiagramAndConciseRequestOverridesDevDefaults() {
        AskContext context = AskContext.builder()
                .question("不用画图，只要告诉我改什么配置就行")
                .mode("dev")
                .build();

        assertFalse(context.useFlowchart());
        assertTrue(context.prefersConciseAnswer());
        assertTrue(context.hasExplicitResponsePreference());
    }

    @Test
    void diagramIsOptInWhenRequestFieldIsAbsent() {
        assertFalse(AskContext.builder().question("分析这个接口").mode("dev").build().useFlowchart());
        assertTrue(AskContext.builder().question("分析这个接口并画流程图").mode("dev").build().useFlowchart());
    }

    @Test
    void explicitRequestFieldHasHighestPriority() {
        AskContext context = AskContext.builder()
                .question("请画流程图")
                .flowchart(false)
                .build();

        assertFalse(context.useFlowchart());
        assertTrue(context.hasExplicitResponsePreference());
    }

    @Test
    void exactApiConfigQuestionUsesTargetedCompletionSemantics() {
        AskContext context = AskContext.builder()
                .question("/features/toggles 这个接口哪个配置可以切换，只告诉我配置")
                .specifiedProjects(List.of("sample-album-bff"))
                .build();

        assertTrue(context.isTargetedConfigLookup());
    }

    @Test
    void explicitDeepTraceNeverUsesTargetedShortcut() {
        AskContext context = AskContext.builder()
                .question("/features/toggles 查到底，完整链路和配置都要")
                .specifiedProjects(List.of("sample-album-bff"))
                .build();

        assertFalse(context.isTargetedConfigLookup());
    }

    @Test
    void directApiLogicQuestionIsRecognizedEvenWhenItAlsoSaysNoDiagram() {
        AskContext context = AskContext.builder()
                .question("/personal/all 这个接口的逻辑是什么 不用画流程图")
                .mode("dev")
                .build();

        assertTrue(context.isInterfaceLogicQuestion());
        assertFalse(context.useFlowchart());
        assertTrue(context.hasExplicitResponsePreference());
        assertTrue("/personal/all".equals(context.detectedApiPath()));
    }

    @Test
    void exactGatewayRouteQuestionUsesTargetedLookupUnlessDeepTraceRequested() {
        AskContext targeted = AskContext.builder()
                .question("apisix 有没有把 /api/v1/items/batch/delete 转发到 backend-service")
                .build();
        AskContext deep = AskContext.builder()
                .question("查到底：apisix 把 /api/v1/items/batch/delete 转发到 backend-service 后的完整链路")
                .build();

        assertTrue(targeted.isTargetedGatewayLookup());
        assertFalse(deep.isTargetedGatewayLookup());
        assertTrue("backend-service".equals(AgentExecutor.gatewayTarget(targeted.getQuestion())));
    }

    @Test
    void noDiagramPreferenceIsEnforcedAfterModelGeneration() {
        AskContext context = AskContext.builder()
                .question("不用画流程图")
                .build();
        String generated = "前言\n\n```mermaid\nflowchart TD\nA-->B\n```\n\n正文结论";

        String answer = context.enforceOutputPreferences(generated);

        assertFalse(answer.contains("mermaid"));
        assertFalse(answer.contains("flowchart TD"));
        assertTrue(answer.contains("前言"));
        assertTrue(answer.contains("正文结论"));
    }

    @Test
    void conciseFinalPromptDropsDiagramAndReportTemplate() {
        AgentExecutor executor = new AgentExecutor(
                mock(LlmClient.class),
                mock(CodeSearcher.class),
                mock(ProjectsConfigLoader.class),
                mock(ProjectsProperties.class),
                mock(SearchProperties.class),
                mock(AgentProperties.class),
                mock(VectorSearchService.class),
                mock(MarkdownDocumentIndex.class),
                mock(ToolExecutionGuard.class));

        String prompt = executor.buildFinalSystemPrompt("dev", List.of("sample-album-bff"), false, true);

        assertTrue(prompt.contains("只回答用户点名要的内容"));
        assertTrue(prompt.contains("不生成 Mermaid/流程图"));
        assertTrue(prompt.contains("不附汇总表"));
        assertTrue(prompt.contains("80～250"));
        assertFalse(prompt.contains("回答开头先输出 mermaid"));
    }
}
