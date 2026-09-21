package com.gptr.engine.plan;

import com.gptr.integration.client.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子查询规划器测试：JSON 解析容错（裸 JSON / markdown 包裹 / 非法退化）。
 */
class SubQueryPlannerTest {

    @Test
    void parsesPlainJson() {
        LlmClient llm = fixed("{\"queries\":[{\"query\":\"q1\",\"researchGoal\":\"g1\"},"
                + "{\"query\":\"q2\",\"researchGoal\":\"g2\"}]}");
        List<SubQuery> queries = new SubQueryPlanner(llm).plan("topic", 3);
        assertEquals(2, queries.size());
        assertEquals("q1", queries.get(0).query());
        assertEquals("g2", queries.get(1).researchGoal());
    }

    @Test
    void parsesMarkdownWrappedJson() {
        LlmClient llm = fixed("```json\n{\"queries\":[{\"query\":\"q1\",\"researchGoal\":\"g1\"}]}\n```");
        List<SubQuery> queries = new SubQueryPlanner(llm).plan("topic", 3);
        assertEquals(1, queries.size());
        assertEquals("q1", queries.get(0).query());
    }

    @Test
    void capsAtMaxQueries() {
        LlmClient llm = fixed("{\"queries\":[{\"query\":\"a\",\"researchGoal\":\"\"},"
                + "{\"query\":\"b\",\"researchGoal\":\"\"},{\"query\":\"c\",\"researchGoal\":\"\"}]}");
        List<SubQuery> queries = new SubQueryPlanner(llm).plan("topic", 2);
        assertEquals(2, queries.size());
    }

    @Test
    void unparseableDegradesToSingleQuery() {
        LlmClient llm = fixed("抱歉，我无法完成该请求。");
        List<SubQuery> queries = new SubQueryPlanner(llm).plan("original topic", 3);
        assertEquals(1, queries.size());
        assertEquals("original topic", queries.get(0).query(), "fallback must keep the original query");
    }

    @Test
    void emptyQueriesDegrade() {
        LlmClient llm = fixed("{\"queries\":[]}");
        List<SubQuery> queries = new SubQueryPlanner(llm).plan("topic", 3);
        assertTrue(queries.isEmpty() || queries.get(0).query().equals("topic"));
    }

    private static LlmClient fixed(String response) {
        return new LlmClient() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                return response;
            }
        };
    }
}
