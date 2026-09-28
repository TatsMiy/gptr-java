package com.gptr.engine.epoc;

import com.gptr.integration.client.ScrapeOutcome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每任务"抓取体检"的聚合载体：把逐 URL 的交代按固定分桶累加，并留一份有上限的失败样本。
 *
 * <p><b>跨轮累加</b>：一个任务每轮抓取各产出一份增量，合并时计数相加；失败样本拼接后重新按上限
 * 截断，被截掉的条数如实记进 {@code failuresOmitted} —— 否则"样本只有 20 条"会被读成
 * "总共只有 20 条失败"。
 *
 * <p><b>分桶口径</b>：每个 outcome **恰好进一个桶**，故五桶之和 == 尝试的 URL 数（可机械对账）；
 * {@code truncated} 是**子集视图而不是桶**（可与"降级后仍成功"同时成立）。
 *
 * <p>⚠️ 复用 {@code DeepResearchState#accumulateStats} 是不行的：它只搬 {@code Number}
 * （非数值键会被**静默丢弃**），而本载体含一个列表。故合并逻辑在此独立实现。
 */
public final class ScrapeHealth {

    /** 落库的失败样本上限；超出只留计数。 */
    public static final int SAMPLE_FAILURES_CAP = 20;

    public static final String COUNTS = "counts";
    public static final String FAILURES = "failures";
    public static final String FAILURES_OMITTED = "failuresOmitted";

    /** 五个桶 + 一个子集视图；名字即落库字段名，面板直接读。 */
    public static final String OK_CLEAN = "okClean";
    public static final String OK_DEGRADED = "okDegraded";
    public static final String BLOCKED_OR_LOGIN = "blockedOrLogin";
    public static final String SILENT_DROPPED = "silentDropped";
    public static final String FAILED = "failed";
    public static final String TRUNCATED = "truncated";

    /** 后端取值域里本类需要认的三个码；其余一律计入 {@code failed} —— 不复制整份词表。 */
    static final String REASON_OK = "ok";
    static final String REASON_BLOCK = "block_challenge";
    static final String REASON_UNKNOWN = "unknown";

    private ScrapeHealth() {
    }

    public static Map<String, Object> empty() {
        return pack(new LinkedHashMap<>(), new ArrayList<>(), 0);
    }

    /** 把一批 outcome 归成一份增量。 */
    public static Map<String, Object> tally(List<ScrapeOutcome> outcomes) {
        Map<String, Object> counts = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        int omitted = 0;
        for (ScrapeOutcome o : outcomes) {
            bump(counts, bucketOf(o));
            if (o.truncated()) {
                bump(counts, TRUNCATED);
            }
            if (!o.usable()) {
                if (failures.size() < SAMPLE_FAILURES_CAP) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("url", o.url());
                    sample.put("reason", o.reason());
                    failures.add(sample);
                } else {
                    omitted++;
                }
            }
        }
        return pack(counts, failures, omitted);
    }

    /** 合并两份体检（跨轮）。 */
    public static Map<String, Object> merge(Map<String, Object> previous, Map<String, Object> delta) {
        Map<String, Object> counts = new LinkedHashMap<>();
        addInto(counts, previous == null ? null : previous.get(COUNTS));
        addInto(counts, delta == null ? null : delta.get(COUNTS));

        int omitted = intOf(previous == null ? null : previous.get(FAILURES_OMITTED))
                + intOf(delta == null ? null : delta.get(FAILURES_OMITTED));
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Object src : new Object[]{previous, delta}) {
            Map<String, Object> m = asMap(src);
            for (Object f : asList(m.get(FAILURES))) {
                if (failures.size() < SAMPLE_FAILURES_CAP) {
                    failures.add(asMap(f));
                } else {
                    omitted++;
                }
            }
        }
        return pack(counts, failures, omitted);
    }

    /** 某个桶的计数（缺键记 0）。 */
    public static int count(Map<String, Object> health, String bucket) {
        return health == null ? 0 : intOf(asMap(health.get(COUNTS)).get(bucket));
    }

    /** 失败样本（url + reason），至多 {@link #SAMPLE_FAILURES_CAP} 条。 */
    public static List<Map<String, Object>> samples(Map<String, Object> health) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (health != null) {
            for (Object f : asList(health.get(FAILURES))) {
                out.add(asMap(f));
            }
        }
        return out;
    }

    /** 因超出上限而未采样的失败条数（缺键记 0）。 */
    public static int failuresOmitted(Map<String, Object> health) {
        return health == null ? 0 : intOf(health.get(FAILURES_OMITTED));
    }

    static String bucketOf(ScrapeOutcome o) {
        if (o.usable()) {
            return (!o.truncated() && !o.degraded()) ? OK_CLEAN : OK_DEGRADED;
        }
        if (REASON_BLOCK.equals(o.reason())) {
            return BLOCKED_OR_LOGIN;
        }
        if (REASON_UNKNOWN.equals(o.reason())) {
            return SILENT_DROPPED;
        }
        return FAILED;
    }

    private static Map<String, Object> pack(Map<String, Object> counts,
                                            List<Map<String, Object>> failures, int omitted) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(COUNTS, counts);
        out.put(FAILURES, failures);
        out.put(FAILURES_OMITTED, omitted);
        return out;
    }

    private static void bump(Map<String, Object> counts, String key) {
        counts.merge(key, 1, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
    }

    private static void addInto(Map<String, Object> target, Object source) {
        for (Map.Entry<String, Object> e : asMap(source).entrySet()) {
            target.merge(e.getKey(), intOf(e.getValue()),
                    (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }
}
