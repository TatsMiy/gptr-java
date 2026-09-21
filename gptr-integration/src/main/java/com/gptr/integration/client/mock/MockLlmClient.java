package com.gptr.integration.client.mock;

import com.gptr.integration.client.LlmClient;
import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟 LLM 客户端。
 *
 * <p>行为模式（{@code mode}）：
 * <ul>
 *   <li>{@code ok}：返回固定回复</li>
 *   <li>{@code transient-then-ok}：前 N 次抛瞬时错误后成功（验证重试恢复）</li>
 *   <li>{@code quota}：每次抛 {@link QuotaApiException}（429 持续打满）</li>
 *   <li>{@code permanent}：抛 {@link PermanentApiException}</li>
 *   <li>{@code json}：{@link #chatJson} 按 prompt 返回结构化 JSON（deep research 端到端测试用），
 *       且 {@link #lastCallCostUsd()} 返回 0.001 以便验证成本通道</li>
 * </ul>
 */
public class MockLlmClient implements LlmClient {

    public enum Mode { OK, TRANSIENT_THEN_OK, QUOTA, PERMANENT, JSON }

    private static final String QUERIES_JSON =
            "[{\"query\":\"q1\",\"researchGoal\":\"g1\"},{\"query\":\"q2\",\"researchGoal\":\"g2\"}]";
    /** 深研提炼响应（含 follow-ups）。sourceUrl 必须 ∈ mock 检索集
     *  （MockSearchClient 输出 https://example.com/<name>/{1,2}，tavily:ok → tavily/1、tavily/2）
     *  ——否则 note 成孤儿被剥，逐节写作主链无证据可写。 */
    private static final String LEARNINGS_JSON =
            "{\"learnings\":[{\"insight\":\"learning-a\",\"sourceUrl\":\"https://example.com/tavily/1\"},"
                    + "{\"insight\":\"learning-b\",\"sourceUrl\":\"https://example.com/tavily/2\"}],"
                    + "\"followUpQuestions\":[\"f1\",\"f2\"]}";
    /** research-plan 澄清前奏响应（system 含 "explore different aspects"）。 */
    private static final String PLAN_JSON =
            "{\"questions\":[\"direction-q1\",\"direction-q2\",\"direction-q3\"]}";

    /** report-outline 大纲响应（system 含 "research report planner"）。subQueryIdx
     *  与输入子查询对齐（q1/q2 → 0/1），走逐节写作主链。 */
    private static final String OUTLINE_JSON =
            "{\"title\":\"Mock 深研报告\",\"sections\":["
                    + "{\"title\":\"背景与机制\",\"goal\":\"说明主题背景\","
                    + "\"queries\":[\"q1\"],\"subQueryIdx\":[0]},"
                    + "{\"title\":\"影响与展望\",\"goal\":\"总结影响\","
                    + "\"queries\":[\"q2\"],\"subQueryIdx\":[1]}]}";

    /** 单节写作响应（system 含 "writing one section"）——节标题 ## + 授权链接
     *  （链接 ∈ mock 检索集 tavily/1、tavily/2，节级引用闸门才放行）。 */
    private static final String SECTION_MD =
            "## Mock 研究节\n\nMock 单节正文：研究引擎按大纲逐节写作，"
                    + "引用锚定授权来源 ([来源一](https://example.com/tavily/1)) 与 "
                    + "([来源二](https://example.com/tavily/2))。\n";

    /** Key Takeaways 响应（system 含 "takeaways"）。 */
    private static final String TAKEAWAYS_MD =
            "- 要点一：大纲先行逐节写作已跑通\n- 要点二：引用闸门按节校验\n";

    private final String name;
    private final Mode mode;
    private final int transientCount;
    private final AtomicInteger calls = new AtomicInteger();

    public MockLlmClient(String name, Mode mode) {
        this(name, mode, 2);
    }

    public MockLlmClient(String name, Mode mode, int transientCount) {
        this.name = name;
        this.mode = mode;
        this.transientCount = transientCount;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        int n = calls.incrementAndGet();
        switch (mode) {
            case TRANSIENT_THEN_OK -> {
                if (n <= transientCount) {
                    throw new TransientApiException(name, "simulated transient failure, call #" + n);
                }
                return "# Mock LLM reply\n\nrecovered after " + n + " calls.";
            }
            case QUOTA -> throw new QuotaApiException(name, "simulated quota exhausted, call #" + n);
            case PERMANENT -> throw new PermanentApiException(name, "simulated permanent error");
            case OK -> {
                return "# Mock LLM reply\n\ncall #" + n;
            }
            case JSON -> {
                // 契约分流：逐节写作（chat）/ outline（chatJson）按 system 识别；
                // 兜底 = 单遍 mock 报告（flat 写作与未知调用保持原语义）
                if (systemPrompt.contains("writing one section")) {
                    return SECTION_MD;
                }
                if (systemPrompt.contains("takeaways")) {
                    return TAKEAWAYS_MD;
                }
                return "# Mock Research Report\n\n模拟报告正文。\n\n## References\n- https://a.com\n- https://b.com";
            }
            default -> throw new IllegalStateException("unknown mode " + mode);
        }
    }

    @Override
    public String chatJson(String systemPrompt, String userPrompt) {
        if (mode == Mode.JSON) {
            if (systemPrompt.contains("generating search queries")
                    || systemPrompt.contains("deepening an ongoing")) {
                return QUERIES_JSON;
            }
            if (systemPrompt.contains("explore different aspects")) {
                return PLAN_JSON;
            }
            if (systemPrompt.contains("research report planner")) {
                return OUTLINE_JSON; // P2-2 大纲契约（否则回 learnings 导致降级单遍假绿）
            }
            return LEARNINGS_JSON;
        }
        return chat(systemPrompt, userPrompt);
    }

    @Override
    public double lastCallCostUsd() {
        return mode == Mode.JSON ? 0.001 : 0.0;
    }
}
