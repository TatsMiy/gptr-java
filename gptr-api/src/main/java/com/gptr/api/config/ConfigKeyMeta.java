package com.gptr.api.config;

import com.gptr.common.config.RetrieverKeyNames;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 可管理配置键白名单（api 校验 + 前端展示共用元数据）。
 *
 * <p>只允许面板管理的键集合；键名与 worker 装配属性对应（无 {@code gptr.clients.}
 * 前缀——由 worker 启动读取时映射，避免 DB 键与 Spring 属性命名耦合）。
 * 白名单外的键一律 400，杜绝任意键注入 app_config。
 */
public record ConfigKeyMeta(
        String key,
        String description,
        boolean secret,
        Predicate<String> validator) {

    /** 模型名最大长度（超长多半是误粘贴了别的内容）。 */
    private static final int MAX_MODEL_NAME_CHARS = 100;

    /** 检索链最大长度（逗号分隔的降级链，过长无实际意义）。 */
    private static final int MAX_SEARCH_CHAIN_CHARS = 200;

    public static final ConfigKeyMeta LLM_PROVIDER = new ConfigKeyMeta(
            "llm.provider",
            "LLM 提供方：openai（OpenAI 兼容，默认 DeepSeek）| mock（配置驱动测试）",
            false,
            v -> v != null && (v.equalsIgnoreCase("openai") || v.equalsIgnoreCase("mock")));

    public static final ConfigKeyMeta LLM_MODEL = new ConfigKeyMeta(
            "llm.model",
            "LLM 模型名（如 deepseek-chat / deepseek-reasoner）",
            false,
            v -> v != null && !v.isBlank() && v.length() <= MAX_MODEL_NAME_CHARS);

    public static final ConfigKeyMeta LLM_BASE_URL = new ConfigKeyMeta(
            "llm.base-url",
            "OpenAI 兼容 API 端点（如 https://api.deepseek.com）",
            false,
            v -> v != null && v.matches("https?://\\S+"));

    public static final ConfigKeyMeta LLM_API_KEY = new ConfigKeyMeta(
            "llm.api-key",
            "LLM API Key（secret：不回显明文，仅显示已设置/未设置）",
            true,
            v -> v != null && !v.isBlank());

    public static final ConfigKeyMeta SEARCH_CHAIN = new ConfigKeyMeta(
            "search.chain",
            "检索降级链（逗号分隔：duckduckgo / python；或 name:mode mock）",
            false,
            v -> v != null && !v.isBlank() && v.length() <= MAX_SEARCH_CHAIN_CHARS);

    public static final ConfigKeyMeta CRAWLER_BASE_URL = new ConfigKeyMeta(
            "crawler.base-url",
            "Python 爬虫服务地址（/search /scrape）",
            false,
            v -> v != null && v.matches("https?://\\S+"));

    /** 检索器 key 键位（键名由 common 的 {@link RetrieverKeyNames} 单一定义，此处不重复拼串）。 */
    private static ConfigKeyMeta retrieverKey(String name, String envVar) {
        return new ConfigKeyMeta(RetrieverKeyNames.apiKeyConfigKey(name),
                "检索器 " + name + " 的 API Key（secret；未填则回落环境变量 " + envVar + "）",
                true,
                v -> v != null && !v.isBlank());
    }

    /** Google 检索器的 CX（搜索引擎 ID）—— 与 api-key 并列的第二个键。 */
    public static final ConfigKeyMeta RETRIEVER_GOOGLE_CX = new ConfigKeyMeta(
            RetrieverKeyNames.googleCxConfigKey(),
            "Google 检索器的 CX（搜索引擎 ID）（secret；未填则回落 GOOGLE_CX_KEY）",
            true,
            v -> v != null && !v.isBlank());

    /** 16 个检索器键（15 个 api-key + google 的 cx-key）。 */
    public static final List<ConfigKeyMeta> RETRIEVER_KEYS = List.of(
            retrieverKey("bing", "BING_API_KEY"),
            retrieverKey("bocha", "BOCHA_API_KEY"),
            retrieverKey("brave", "BRAVE_API_KEY"),
            retrieverKey("crw", "CRW_API_KEY"),
            retrieverKey("exa", "EXA_API_KEY"),
            retrieverKey("getxapi", "GETXAPI_API_KEY"),
            retrieverKey("google", "GOOGLE_API_KEY"),
            RETRIEVER_GOOGLE_CX,
            retrieverKey("groundroute", "GROUNDROUTE_API_KEY"),
            retrieverKey("openalex", "OPENALEX_API_KEY"),
            retrieverKey("pubmed-central", "NCBI_API_KEY"),
            retrieverKey("searchapi", "SEARCHAPI_API_KEY"),
            retrieverKey("serpapi", "SERPAPI_API_KEY"),
            retrieverKey("serper", "SERPER_API_KEY"),
            retrieverKey("tavily", "TAVILY_API_KEY"),
            retrieverKey("xquik", "XQUIK_API_KEY"));

    public static final List<ConfigKeyMeta> ALL = java.util.stream.Stream.concat(
            List.of(LLM_PROVIDER, LLM_MODEL, LLM_BASE_URL, LLM_API_KEY,
                    SEARCH_CHAIN, CRAWLER_BASE_URL).stream(),
            RETRIEVER_KEYS.stream()).toList();

    public static Optional<ConfigKeyMeta> byKey(String key) {
        return ALL.stream().filter(m -> m.key().equals(key)).findFirst();
    }
}
