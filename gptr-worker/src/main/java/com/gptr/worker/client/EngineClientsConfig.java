package com.gptr.worker.client;

import com.gptr.engine.llm.OpenAiCompatLlmClient;
import com.gptr.engine.scrape.PythonCrawlerScraperClient;
import com.gptr.common.config.RetrieverKeyNames;
import com.gptr.engine.search.DuckDuckGoSearchClient;
import com.gptr.engine.search.PythonCrawlerSearchClient;
import com.gptr.engine.search.RetrieverKeys;
import com.gptr.integration.client.LlmClient;
import com.gptr.integration.client.ScraperClient;
import com.gptr.integration.client.ScrapedContent;
import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchOptions;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import com.gptr.integration.client.mock.MockLlmClient;
import com.gptr.integration.client.mock.MockSearchClient;
import com.gptr.integration.resilience.FallbackChain;
import com.gptr.integration.resilience.ResilienceBeans;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎外部客户端装配：
 *
 * <ul>
 *   <li>检索降级链：{@code name[:mode]} 列表——无 mode 的真实源（当前支持
 *       {@code duckduckgo}，零 key 免费）与 {@code name:mode} 的 mock 源混排；
 *       每个源包装重试+熔断，由 {@link FallbackChain} 编排。</li>
 *   <li>LLM：{@code gptr.clients.llm-provider=mock|openai}——mock 为配置驱动行为；
 *       openai 为 OpenAI 兼容客户端（默认 DeepSeek），无 {@code DEEPSEEK_API_KEY}
 *       时自动降级 mock。</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EngineClientsConfig {

    // ── 弹性层统一策略（5 个外部源一致，见 retry/breaker 两个工厂）────────────────

    /** 重试次数（5 个外部源一致）。 */
    private static final int RETRY_MAX_ATTEMPTS = 3;

    /** 退避倍增因子（5 个外部源一致）。 */
    private static final int RETRY_MULTIPLIER = 2;

    /** 熔断滑动窗口大小（5 个外部源一致）。 */
    private static final int BREAKER_WINDOW_SIZE = 10;

    /** 熔断失败率阈值（%）。 */
    private static final int BREAKER_FAILURE_RATE_PERCENT = 50;

    /** 熔断打开时长（秒）。 */
    private static final int BREAKER_OPEN_DURATION_SECONDS = 10;

    // ── 各源重试的初始退避（毫秒）——保留差异：真实外部服务 300，LLM 与 mock 200 ──

    private static final long RETRY_WAIT_MS_LLM = 200;
    private static final long RETRY_WAIT_MS_SCRAPE = 300;
    private static final long RETRY_WAIT_MS_SEARCH_MOCK = 200;
    private static final long RETRY_WAIT_MS_SEARCH_DDG = 300;
    private static final long RETRY_WAIT_MS_SEARCH_PYTHON = 300;

    /** 检索降级链："duckduckgo,tavily:transient,serper:ok,..."（无 mode = 真实源）。 */
    @Value("${gptr.clients.search-chain:duckduckgo}")
    private String searchChain;

    /** LLM provider：mock | openai。 */
    @Value("${gptr.clients.llm-provider:mock}")
    private String llmProvider;

    /** mock LLM 行为模式（ok/transient-then-ok/quota/permanent）。 */
    @Value("${gptr.clients.llm-mode:ok}")
    private String llmMode;

    @Value("${gptr.clients.llm-base-url:https://api.deepseek.com}")
    private String llmBaseUrl;

    @Value("${gptr.clients.llm-model:deepseek-chat}")
    private String llmModel;

    /** Python 爬虫服务地址（/search /scrape）。 */
    @Value("${gptr.clients.crawler-base-url:http://127.0.0.1:8000}")
    private String crawlerBaseUrl;

    /**
     * app_config 覆盖读取（键无 {@code gptr.clients.} 前缀，见 ConfigKeyMeta）。
     * 表缺失/查询异常/容器未就绪（早期场景）→ 用 yml 默认。secret 键由调用处负责不落日志。
     *
     * <p>评审加固：经 {@link ObjectProvider} 懒取 JdbcTemplate——@Configuration 早期
     * 实例化不强制触发 DataSource/连接链；仅在 @Bean 方法（客户端装配）执行时才取，
     * 取不到（如非 DB 场景）安全回退默认值。本类与 DataSource 间无环（Spring 单例
     * 依赖向下单向），此改动消除的是早期初始化耦合面而非修复循环依赖。
     */
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbcTemplateProvider;

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return jdbcTemplateProvider.getIfAvailable();
    }

    private String cfg(String dbKey, String fallback) {
        try {
            org.springframework.jdbc.core.JdbcTemplate jt = jdbc();
            if (jt == null) {
                return fallback; // 容器未就绪/非 DB 场景
            }
            java.util.List<String> vals = jt.query(
                    "SELECT value FROM app_config WHERE key = ?",
                    (rs, i) -> rs.getString(1), dbKey);
            if (!vals.isEmpty() && vals.get(0) != null && !vals.get(0).isBlank()) {
                log.info("app_config override {} (set in dashboard)", dbKey);
                return vals.get(0);
            }
        } catch (Exception e) {
            log.debug("app_config read failed for {} (use default): {}", dbKey, e.getMessage());
        }
        return fallback;
    }

    @Bean
    public SearchClient searchClient() {
        String crawler = cfg("crawler.base-url", crawlerBaseUrl);
        List<Source> sources = parseChain(cfg("search.chain", searchChain), crawler);
        return new SearchClient() {
            @Override
            public String name() {
                return "search-chain[" + sources.stream()
                        .map(s -> s.client.name()).reduce((a, b) -> a + "," + b).orElse("") + "]";
            }

            @Override
            public SearchResponse search(String query) {
                return search(query, SearchOptions.DEFAULT);
            }

            @Override
            // 每个步骤返回自带源名的 SearchResponse，短路成功后 sourceUsed=
            // 实际命中步（评审 #2：命中源随结果显式返回，不靠隐式上下文）
            public SearchResponse search(String query, SearchOptions opts) {
                List<FallbackChain.Step<SearchResponse>> steps = sources.stream()
                        .map(s -> new FallbackChain.Step<>(s.client.name(),
                                ResilienceBeans.protectedSupplier(
                                        () -> new SearchResponse(
                                                s.client.search(query, opts).results(), s.client.name()),
                                        s.retry, s.breaker)))
                        .toList();
                return FallbackChain.execute(steps);
            }
        };
    }

    @Bean
    public LlmClient llmClient() {
        String provider = cfg("llm.provider", llmProvider);
        if ("openai".equalsIgnoreCase(provider)) {
            // app_config llm.api-key（secret）优先，回退进程环境变量
            String apiKey = cfg("llm.api-key", null);
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getenv("DEEPSEEK_API_KEY");
            }
            if (apiKey == null || apiKey.isBlank()) {
                // fail-fast：声明 openai 却缺 key → 启动失败而非静默降级 mock
                // （否则生产任务会"成功"产出 mock 垃圾报告）
                throw new IllegalStateException(
                        "gptr.clients.llm-provider=openai but no LLM api key — set llm.api-key "
                                + "in dashboard config or DEEPSEEK_API_KEY env; refusing to start "
                                + "with mock fallback");
            }
            String baseUrl = cfg("llm.base-url", llmBaseUrl);
            String model = cfg("llm.model", llmModel);
            log.info("LLM provider: openai-compatible ({}, model {})", baseUrl, model);
            OpenAiCompatLlmClient client = new OpenAiCompatLlmClient(
                    "llm", baseUrl, apiKey, model);
            return protectLlm(client);
        }
        MockLlmClient client = new MockLlmClient("llm", parseLlmMode(llmMode));
        return protectLlm(client);
    }

    private LlmClient protectLlm(LlmClient client) {
        Retry retry = retry("llm", RETRY_WAIT_MS_LLM);
        CircuitBreaker breaker = breaker("llm");
        return new LlmClient() {
            @Override
            public String name() {
                return client.name();
            }

            @Override
            public String chat(String systemPrompt, String userPrompt) {
                return ResilienceBeans.protectedSupplier(
                        () -> client.chat(systemPrompt, userPrompt), retry, breaker).get();
            }

            @Override
            public String chatJson(String systemPrompt, String userPrompt) {
                return ResilienceBeans.protectedSupplier(
                        () -> client.chatJson(systemPrompt, userPrompt), retry, breaker).get();
            }

            @Override
            public double lastCallCostUsd() {
                return client.lastCallCostUsd();
            }
        };
    }

    /** 抓取客户端：调 Python /scrape，经重试+熔断保护（引擎 SCRAPING 阶段使用）。 */
    @Bean
    public ScraperClient scraperClient() {
        String crawler = cfg("crawler.base-url", crawlerBaseUrl);
        PythonCrawlerScraperClient client = new PythonCrawlerScraperClient(crawler);
        Retry retry = retry("scrape-python-crawler", RETRY_WAIT_MS_SCRAPE);
        CircuitBreaker breaker = breaker("scrape-python-crawler");
        log.info("scraper source: python-crawler ({})", crawlerBaseUrl);
        return new ScraperClient() {
            @Override
            public String name() {
                return client.name();
            }

            @Override
            public java.util.List<ScrapedContent> scrape(java.util.List<String> urls) {
                return ResilienceBeans.protectedSupplier(() -> client.scrape(urls), retry, breaker).get();
            }

            @Override
            public java.util.List<ScrapedContent> scrape(java.util.List<String> urls, int maxCharsPerUrl) {
                // 修复（B 臂实测）：此前未 override 两参版本 → 掉进 ScraperClient 接口
                // default 实现丢弃 maxChars → crawler 恒 4000 截断——flat 蒸馏与 deep 选句
                // 蒸馏从未真正拿到长文（mock 测试掩盖）。两参透传，重试/熔断语义与单参一致。
                return ResilienceBeans.protectedSupplier(() -> client.scrape(urls, maxCharsPerUrl),
                        retry, breaker).get();
            }
        };
    }

    /** 图内 checkpoint saver（deep research 图状态持久化到 Postgres）。 */
    @Bean
    public com.gptr.engine.epoc.PostgresCheckpointSaver checkpointSaver(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new com.gptr.engine.epoc.PostgresCheckpointSaver(jdbcTemplate);
    }

    private List<Source> parseChain(String chain, String crawler) {
        List<Source> sources = new ArrayList<>();
        for (String entry : chain.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":", 2);
            String name = parts[0].trim();
            boolean real = parts.length == 1; // 无 mode = 真实源
            if (real) {
                sources.add(realSource(name, crawler));
            } else {
                String mode = parts[1].trim();
                MockSearchClient client = new MockSearchClient(name, parseSearchMode(mode));
                Retry retry = retry("search-" + name, RETRY_WAIT_MS_SEARCH_MOCK);
                CircuitBreaker breaker = breaker("search-" + name);
                sources.add(new Source(client, retry, breaker));
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("gptr.clients.search-chain is empty");
        }
        return sources;
    }

    /**
     * 检索器 key 表：从 {@code app_config} 读检索器键，**缺失的检索器不入表**
     * （⇒ crawler 侧回落环境变量）。
     *
     * <p>⚠️ secret 纪律：只记录"哪些检索器已配置"，**绝不打印 key 值**（与 {@code llm.api-key} 同规）。
     */
    private RetrieverKeys retrieverKeys() {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String name : RetrieverKeyNames.API_KEY_RETRIEVERS) {
            String v = cfg(RetrieverKeyNames.apiKeyConfigKey(name), null);
            if (v != null && !v.isBlank()) {
                byName.put(name, v);
            }
        }
        String googleCx = cfg(RetrieverKeyNames.googleCxConfigKey(), null);
        log.info("retriever keys from dashboard: configured={} googleCx={}",
                byName.keySet(), googleCx == null ? "unset" : "set");
        return new RetrieverKeys(byName, googleCx);
    }

    private Source realSource(String name, String crawler) {
        return switch (name.toLowerCase()) {
            case "duckduckgo" -> {
                DuckDuckGoSearchClient client = new DuckDuckGoSearchClient();
                Retry retry = retry("search-duckduckgo", RETRY_WAIT_MS_SEARCH_DDG);
                CircuitBreaker breaker = breaker("search-duckduckgo");
                log.info("search source: duckduckgo (zero-key, real)");
                yield new Source(client, retry, breaker);
            }
            case "python" -> {
                PythonCrawlerSearchClient client = new PythonCrawlerSearchClient(
                        crawler, PythonCrawlerSearchClient.DEFAULT_RETRIEVER,
                        PythonCrawlerSearchClient.DEFAULT_MAX_RESULTS, retrieverKeys());
                Retry retry = retry("search-python-crawler", RETRY_WAIT_MS_SEARCH_PYTHON);
                CircuitBreaker breaker = breaker("search-python-crawler");
                log.info("search source: python-crawler ({})", crawler);
                yield new Source(client, retry, breaker);
            }
            default -> throw new IllegalArgumentException(
                    "unknown real search source '" + name + "' (supported: duckduckgo, python; or use name:mode for mock)");
        };
    }

    private static MockSearchClient.Mode parseSearchMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "ok" -> MockSearchClient.Mode.OK;
            case "transient" -> MockSearchClient.Mode.TRANSIENT;
            case "quota" -> MockSearchClient.Mode.QUOTA;
            case "permanent" -> MockSearchClient.Mode.PERMANENT;
            default -> throw new IllegalArgumentException("unknown search mode: " + mode);
        };
    }

    private static MockLlmClient.Mode parseLlmMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "ok" -> MockLlmClient.Mode.OK;
            case "transient-then-ok" -> MockLlmClient.Mode.TRANSIENT_THEN_OK;
            case "quota" -> MockLlmClient.Mode.QUOTA;
            case "permanent" -> MockLlmClient.Mode.PERMANENT;
            case "json" -> MockLlmClient.Mode.JSON;
            default -> throw new IllegalArgumentException("unknown llm mode: " + mode);
        };
    }

    /** 统一重试策略；初始退避按源传入（各源不同，见 {@code RETRY_WAIT_MS_*} 常量）。 */
    private static Retry retry(String name, long initialWaitMs) {
        return ResilienceBeans.retry(name, RETRY_MAX_ATTEMPTS, Duration.ofMillis(initialWaitMs),
                RETRY_MULTIPLIER);
    }

    /** 统一熔断策略（5 个外部源参数一致，故不接收参数——避免被单点覆盖）。 */
    private static CircuitBreaker breaker(String name) {
        return ResilienceBeans.breaker(name, BREAKER_WINDOW_SIZE, BREAKER_FAILURE_RATE_PERCENT,
                Duration.ofSeconds(BREAKER_OPEN_DURATION_SECONDS));
    }

    private record Source(SearchClient client, Retry retry, CircuitBreaker breaker) {
    }
}
