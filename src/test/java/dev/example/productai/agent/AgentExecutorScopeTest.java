package dev.example.productai.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutorScopeTest {

    @Test
    void omittedProjectKeysRemainInsideUserSpecifiedProjectsAfterFirstSearch() {
        AgentHarvest harvest = new AgentHarvest();
        harvest.specifiedProjects = List.of("sample-album-bff");
        harvest.searchedProjects.add("sample-album-bff");
        List<String> requested = new ArrayList<>();

        AgentExecutor.applyDefaultProjectScope(requested, harvest);

        assertEquals(List.of("sample-album-bff"), requested);
    }

    @Test
    void explicitCrossServiceProjectIsPreserved() {
        AgentHarvest harvest = new AgentHarvest();
        harvest.specifiedProjects = List.of("sample-album-bff");
        List<String> requested = new ArrayList<>(List.of("sample-content-service"));

        AgentExecutor.applyDefaultProjectScope(requested, harvest);

        assertEquals(List.of("sample-content-service"), requested);
    }

    @Test
    void targetedConfigEvidenceClosesOnlyAfterAllThreeEvidenceClassesExist() {
        AgentHarvest harvest = new AgentHarvest();
        harvest.readFiles.addAll(List.of(
                "sample-album-bff/FeatureToggleController.java",
                "sample-album-bff/FeatureToggleConfigService.java",
                "sample-album-bff/FeatureToggleConfig.java",
                "sample-album-bff/FeatureStatus.java"));
        harvest.materials.addAll(List.of(
                "@RequestMapping(\"/features/toggles\")",
                "if (config.getStatus() == NORMAL) { return result; }",
                "@NacosConfigurationProperties(dataId = \"sample_feature_toggle\", group = \"dev.example.feature-toggle\")",
                "OFF_SHELVE disables the miniapp"));

        assertTrue(AgentExecutor.hasCompleteTargetedConfigEvidence(harvest));

        harvest.readFiles.remove("sample-album-bff/FeatureToggleController.java");
        assertFalse(AgentExecutor.hasCompleteTargetedConfigEvidence(harvest));
    }

    @Test
    void targetedConfigEvidenceDoesNotCloseOnSearchSnippetsAlone() {
        AgentHarvest harvest = new AgentHarvest();
        harvest.materials.addAll(List.of(
                "dataId = sample_feature_toggle", "status", "if (enabled)", "switch config"));

        assertFalse(AgentExecutor.hasCompleteTargetedConfigEvidence(harvest));
    }

    @Test
    void switcherIdLiteralIsLabeledWithoutReversingDataIdAndGroup() {
        AgentHarvest harvest = new AgentHarvest();
        harvest.materials.add("new SwitcherID(\"sample_feature_toggle\", \"dev.example.feature-toggle\", ConfigurationSwitcher.TYPE)");

        assertEquals("SwitcherID：dataId=\"sample_feature_toggle\"，group=\"dev.example.feature-toggle\"",
                AgentExecutor.verifiedConfigFacts(harvest));
    }
}
