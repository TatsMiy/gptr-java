package com.gptr.benchmark.dataset;

import java.util.List;

/**
 * 单条评测题。
 *
 * @param id         唯一 id
 * @param query      研究问题
 * @param mode       研究模式：flat | deep
 * @param lang       语言：zh | en
 * @param cat        题类：general | recency | simpleqa
 * @param superseded 过时答案白名单（wrong_stale 三分类用：报告给出该描述 → stale；
 *                   空 = 该题无 stale 判定），如珠峰高程题旧值 "8844.43米"
 * @param gold       客观题标准答案（开放题可空 → 走幻觉率维度）
 * @param blocked    禁止来源（P0-4，对标 Bench II blocked list）：URL 前缀或域名；
 *                   评测经引擎检索/抓取层直接屏蔽（防"直接引用源文答题"的泄漏），
 *                   报告仍引用则记 leak 并从正确判定中剔除
 */
public record BenchmarkItem(String id, String query, String mode, String lang,
                            String cat, String superseded, String gold, List<String> blocked) {

    public boolean hasGold() {
        return gold != null && !gold.isBlank();
    }

    public boolean hasBlocked() {
        return blocked != null && !blocked.isEmpty();
    }
}
