package com.gptr.engine.search;

import com.gptr.common.config.RetrieverKeyNames;

import java.util.Map;

/**
 * 检索器 API key 表（2026-09-19）。
 *
 *
 * <p>键 = 检索器名（{@code bocha} / {@code pubmed-central} / …），值 = key 明文。
 * 由装配层从 {@code app_config} 读出；**缺失的检索器不入表**
  * （⇒ crawler 侧回落环境变量）。
 *
  * <p>单一来源：键名与 header 名的定义在 {@link RetrieverKeyNames}（common），
 * 此处只做运行时承载，不重复命名规则。
 */
public record RetrieverKeys(Map<String, String> byName, String googleCxKey) {

    /** 空表：无任何可注入 key，等价于"全部回落 env"（改动前行为）。 */
    public static final RetrieverKeys EMPTY = new RetrieverKeys(Map.of(), null);

    /** crawler 侧 header 名：{@code <name>_api_key}（命名委托给 common，见类注释）。 */
    public String headerName(String retriever) {
        return RetrieverKeyNames.apiKeyHeader(retriever);
    }

    /** 该检索器是否有可注入的 key（无 ⇒ 不发 header，由 crawler 回落 env）。 */
    public boolean has(String retriever) {
        return retriever != null && byName.containsKey(retriever);
    }

    public String keyOf(String retriever) {
        return byName.get(retriever);
    }
}
