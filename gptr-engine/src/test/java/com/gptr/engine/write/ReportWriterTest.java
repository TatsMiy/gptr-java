package com.gptr.engine.write;

import com.gptr.integration.client.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告写作器与引用核验器测试。
 */
class ReportWriterTest {

    @Test
    void writeAssemblesPromptWithAuthorizedSources() {
        String[] captured = new String[2];
        LlmClient llm = new LlmClient() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public String chat(String system, String user) {
                captured[0] = system;
                captured[1] = user;
                return "# Report\n\nok";
            }
        };
        ReportWriter writer = new ReportWriter(llm, "中文");
        String report = writer.write("q", "learning-a [source: https://a.com]",
                List.of("https://a.com"));

        assertTrue(report.contains("# Report"));
        assertTrue(captured[0].contains("中文"), "language must be injected");
        assertTrue(captured[1].contains("https://a.com"), "authorized sources in prompt");
        assertTrue(captured[1].contains("References"), "References requirement in prompt");
    }

    @Test
    void citationVerifierKeepsOnlyAuthorizedUrls() {
        CitationVerifier verifier = new CitationVerifier(List.of("https://a.com", "https://b.com"));
        String report = """
                # R
                claim one ([a](https://a.com)) and ([b](https://b.com))
                claim two ([fake](https://evil.example.com/x))
                References:
                - https://a.com
                - https://evil.example.com/x
                """;

        List<String> verified = verifier.verify(report);
        assertEquals(List.of("https://a.com", "https://b.com"), verified,
                "only authorized URLs survive verification");
        assertTrue(verifier.hasUnauthorizedCitations(report), "hallucinated citation must be detected");
    }

    @Test
    void citationVerifierHandlesBlankReport() {
        CitationVerifier verifier = new CitationVerifier(List.of("https://a.com"));
        assertFalse(verifier.hasUnauthorizedCitations(""));
        assertEquals(0, verifier.citedUrls(null).size());
    }
}
