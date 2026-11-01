package dev.example.productai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPropertiesTest {

    @Test
    void usesSixteenTurnsForNormalUserQuestion() {
        AgentProperties properties = new AgentProperties();

        assertFalse(properties.isDeepTraceRequest("这个接口在哪里？", null));
        assertEquals(16, properties.budgetFor("user", false));
        assertEquals(0, properties.graceTurnsFor(false));
    }

    @Test
    void deepTraceIntentRaisesUserBudgetWithoutChangingMode() {
        AgentProperties properties = new AgentProperties();

        assertTrue(properties.isDeepTraceRequest("我要查到底，追到最终数据源", null));
        assertEquals(48, properties.budgetFor("user", true));
        assertEquals(16, properties.graceTurnsFor(true));
        assertEquals(1_000_000, properties.getTokenBudget());
        assertEquals(2_000_000, properties.getTraceTokenBudget());
        assertEquals(48_000, properties.getTraceDecisionContextBytes());
        assertEquals(5, properties.getTraceMaxNoProgressRounds());
    }

    @Test
    void analysisIntentCanAlsoTriggerDeepTrace() {
        AgentProperties properties = new AgentProperties();

        assertTrue(properties.isDeepTraceRequest("分析这个接口", "完整链路，不要停在接口"));
        assertEquals(48, properties.budgetFor("analyze", true));
    }

    @Test
    void allBudgetsRemainUnderHardCap() {
        AgentProperties properties = new AgentProperties();
        properties.setDeepTraceBudget(99);
        properties.setDeepTraceGraceTurns(99);

        assertEquals(AgentProperties.HARD_CAP_BUDGET, properties.budgetFor("user", true));
        assertEquals(AgentProperties.HARD_CAP_BUDGET, properties.graceTurnsFor(true));
    }
}
