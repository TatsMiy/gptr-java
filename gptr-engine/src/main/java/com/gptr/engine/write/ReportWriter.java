package com.gptr.engine.write;


import com.gptr.engine.epoc.DeepResearchPrompts;
import com.gptr.integration.client.LlmClient;
import java.util.List;
import java.util.Map;

/**
 * 报告写作器：基于研究上下文生成带 in-text 引用与 References 的 Markdown 报告。
 *
 * <p>prompt 核心要求提炼自原版 generate_subtopic_report_prompt（Apache-2.0）：
 * in-text markdown 链接引用、只引用提供的来源 URL、章节结构、不虚构内容。
 *
 * <p><b>prompt 取用</b>：统一走 {@link DeepResearchPrompts}（唯一出口）——
 * 本类原先自持一份 {@code Properties} 并手工 {@code replace("{language}", ...)}，
 * 那让"报告语言"这个跨 prompt 的公共变量**只在本类生效**（逐节写作路径整个漏掉）。
 */
public class ReportWriter {

    private final LlmClient llm;
    private final String language;

    public ReportWriter(LlmClient llm, String language) {
        this.llm = llm;
        this.language = language;
    }

    /**
     * 生成报告。
     *
     * @param query     研究问题
     * @param context   研究上下文（learnings，含 [source: url]）
     * @param sourceUrls 授权来源 URL 集合（References 只保留这些）
     * @return Markdown 报告
     */
    public String write(String query, String context, List<String> sourceUrls) {
        String sources = sourceUrls.isEmpty() ? "(none provided)" : String.join("\n", sourceUrls);
        Map<String, String> vars = Map.of(
                "language", language,
                "query", query,
                "context", context == null ? "" : context,
                "sources", sources);
        return llm.chat(DeepResearchPrompts.get("report-write.system", vars),
                DeepResearchPrompts.get("report-write.user", vars));
    }
}
