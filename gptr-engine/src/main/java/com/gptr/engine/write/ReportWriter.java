package com.gptr.engine.write;


import com.gptr.integration.client.LlmClient;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * 报告写作器：基于研究上下文生成带 in-text 引用与 References 的 Markdown 报告。
 *
 * <p>prompt 核心要求提炼自原版 generate_subtopic_report_prompt（Apache-2.0）：
 * in-text markdown 链接引用、只引用提供的来源 URL、章节结构、不虚构内容。
 */
public class ReportWriter {

    private static final Properties PROPS = loadPrompts();

    private final LlmClient llm;
    private final String language;

    public ReportWriter(LlmClient llm) {
        this(llm, "中文");
    }

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
        String system = PROPS.getProperty("report-write.system").replace("{language}", language);
        String user = PROPS.getProperty("report-write.user")
                .replace("{query}", query)
                .replace("{context}", context == null ? "" : context)
                .replace("{sources}", sources)
                .replace("{language}", language);
        return llm.chat(system, user);
    }

    private static Properties loadPrompts() {
        Properties props = new Properties();
        try (var in = ReportWriter.class.getResourceAsStream("/prompts/deep-research.properties")) {
            // UTF-8 显式（properties 含中文值，默认 ISO-8859-1 会乱码）
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load report prompts", e);
        }
        return props;
    }
}
