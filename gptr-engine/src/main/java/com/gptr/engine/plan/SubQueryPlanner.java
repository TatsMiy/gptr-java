package com.gptr.engine.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gptr.integration.client.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子查询规划器（PLANNING 阶段）：LLM 结构化输出
 * {@code {"queries":[{"query":"...","researchGoal":"..."}]}，数量 3~5。
 *
 * <p>JSON 解析容错（对应原版 json_repair 思路的轻量版）：依次尝试
 * ①裸 JSON ②markdown 代码块 ③正则提取第一个 JSON 对象；全部失败则退化为
 * 把原始 query 作为唯一子查询（保证流水线不断）。
 */
@Component
@RequiredArgsConstructor
public class SubQueryPlanner {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public List<SubQuery> plan(String query, int maxQueries) {
        String system = "你是研究规划助手。把用户的研究问题拆解为 " + maxQueries
                + " 个独立、可搜索的子问题，每个子问题聚焦一个方面。"
                + "只输出 JSON，不要任何其他文字，格式："
                + "{\"queries\":[{\"query\":\"子问题\",\"researchGoal\":\"研究目标说明\"}]}";
        String user = "研究问题：" + query;
        String raw = llm.chatJson(system, user);
        List<SubQuery> parsed = parse(raw, maxQueries);
        if (parsed.isEmpty()) {
            // 容错：LLM 输出不可解析时退化为单子查询（原问题），保证流水线继续
            return List.of(new SubQuery(query, "研究原问题：" + query));
        }
        return parsed;
    }

    /** 解析 queries 数组为子查询列表；非数组 → 空，坏元素跳过，至多 maxQueries 条。 */
    private static List<SubQuery> parseSubQueries(JsonNode queries, int maxQueries) {
        if (!queries.isArray()) {
            return List.of();
        }
        List<SubQuery> result = new ArrayList<>();
        for (JsonNode q : queries) {
            String qq = q.path("query").asText(null);
            String goal = q.path("researchGoal").asText("");
            if (qq != null && !qq.isBlank()) {
                result.add(new SubQuery(qq.trim(), goal == null ? "" : goal.trim()));
            }
            if (result.size() >= maxQueries) {
                break;
            }
        }
        return result;
    }

    List<SubQuery> parse(String raw, int maxQueries) {
        for (String candidate : candidates(raw)) {
            try {
                JsonNode root = mapper.readTree(candidate);
                List<SubQuery> result = parseSubQueries(root.path("queries"), maxQueries);
                if (!result.isEmpty()) {
                    return result;
                }
            } catch (Exception ignored) {
                // 尝试下一个候选
            }
        }
        return List.of();
    }

    private List<String> candidates(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            out.add(raw.trim());
            Matcher m = JSON_BLOCK.matcher(raw);
            while (m.find()) {
                out.add(m.group(1));
            }
            Matcher obj = JSON_OBJECT.matcher(raw);
            if (obj.find()) {
                out.add(obj.group());
            }
        }
        return out;
    }
}
