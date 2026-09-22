package com.gptr.common.config;

import java.util.List;

/**
 * 检索器 key 的**命名单一来源**。
 *
 *
 * <p>放在 {@code common} 是因为**三方都要用同一份清单**，而依赖方向是
 * {@code api → common ← worker → engine}：
 * <ul>
 *   <li>{@code gptr-api} —— 生成观测台白名单键（{@code ConfigKeyMeta}）；</li>
 *   <li>{@code gptr-worker} —— 从 {@code app_config} 读出这些键并装配；</li>
 *   <li>{@code gptr-engine} —— 拼 crawler 侧 header 名（{@code RetrieverKeys}）。</li>
 * </ul>
 *
 * <p>这三处**不得各自硬编码**，否则加一个检索器必漏一处
 * （本仓已有"同一个默认值两处声明"的教训）。
 */
public final class RetrieverKeyNames {

    /** 需要 api-key 的检索器（15 个）；无需 key 的（arxiv/searx/custom/…）不在列。 */
    public static final List<String> API_KEY_RETRIEVERS = List.of(
            "bing", "bocha", "brave", "crw", "exa", "getxapi", "google", "groundroute",
            "openalex", "pubmed-central", "searchapi", "serpapi", "serper", "tavily", "xquik");

    /** google 是唯一需要**第二个**键（CX / 搜索引擎 ID）的检索器。 */
    public static final String GOOGLE = "google";

    private RetrieverKeyNames() {
    }

    /** 观测台 / {@code app_config} 的键名：{@code retriever.<name>.api-key}。 */
    public static String apiKeyConfigKey(String retriever) {
        return "retriever." + retriever + ".api-key";
    }

    /** google CX 的配置键名：{@code retriever.google.cx-key}。 */
    public static String googleCxConfigKey() {
        return "retriever." + GOOGLE + ".cx-key";
    }

    /** crawler 侧 header 名：{@code <name>_api_key}（连字符转下划线）。
     *  <p>与 crawler 既有 4 个检索器的读取名一致（tavily_search.py:53 / crw.py:49 /
     *  groundroute.py:26 / google.py:23）。 */
    public static String apiKeyHeader(String retriever) {
        return retriever.replace('-', '_') + "_api_key";
    }

    /** google CX 的 header 名：{@code google_cx_key}（与 google.py:24 一致）。 */
    public static String googleCxHeader() {
        return GOOGLE + "_cx_key";
    }
}
