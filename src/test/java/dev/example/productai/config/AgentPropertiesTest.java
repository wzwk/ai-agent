package dev.example.productai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPropertiesTest {

    @Test
    void usesSixTurnsForNormalUserQuestion() {
        AgentProperties properties = new AgentProperties();

        assertFalse(properties.isDeepTraceRequest("这个接口在哪里？", null));
        assertEquals(6, properties.budgetFor("user", false));
        assertEquals(0, properties.graceTurnsFor(false));
    }

    @Test
    void deepTraceIntentRaisesUserBudgetWithoutChangingMode() {
        AgentProperties properties = new AgentProperties();

        assertTrue(properties.isDeepTraceRequest("我要查到底，追到最终数据源", null));
        assertEquals(12, properties.budgetFor("user", true));
        assertEquals(2, properties.graceTurnsFor(true));
    }

    @Test
    void analysisIntentCanAlsoTriggerDeepTrace() {
        AgentProperties properties = new AgentProperties();

        assertTrue(properties.isDeepTraceRequest("分析这个接口", "完整链路，不要停在接口"));
        assertEquals(12, properties.budgetFor("analyze", true));
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
