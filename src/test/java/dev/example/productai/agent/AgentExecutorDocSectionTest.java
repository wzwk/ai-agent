package dev.example.productai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutorDocSectionTest {

    @Test
    void parentSectionIncludesNestedBackgroundAndHandlingSections() {
        List<String> lines = List.of(
                "## 团队token失效问题处理",
                "",
                "### 背景：",
                "提示 token 版本较旧",
                "### 处理：",
                "调用清理接口后重新登录",
                "## 下一个问题",
                "其他内容");

        int level = AgentExecutor.markdownHeadingLevel(lines.get(0));
        assertEquals(2, level);
        assertEquals(5, AgentExecutor.markdownSectionEnd(lines, 0, level));
    }

    @Test
    void normalizedQuestionCoversExactFaqHeading() {
        String question = AgentExecutor.normalizeDocTitle("这个 团队 Token 失效问题处理");
        String heading = AgentExecutor.normalizeDocTitle("团队token失效问题处理");
        assertTrue(question.contains(heading));
    }

    @Test
    void exactFaqAnswerKeepsStepsAndRedactsToken() {
        AgentHarvest harvest = new AgentHarvest();
        harvest.docHits.put("docs/faq.md", "12|## 团队token失效问题处理\n"
                + "13|调用 https://example.test/clear?token=secret-value 后重新登录\n");

        String answer = AgentExecutor.renderExactFaqAnswer(harvest);

        assertTrue(answer.contains("团队token失效问题处理"));
        assertTrue(answer.contains("token={已脱敏}"));
        assertTrue(!answer.contains("secret-value"));
    }
}
