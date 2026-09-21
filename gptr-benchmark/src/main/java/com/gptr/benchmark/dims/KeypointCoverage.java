package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.JsonNode;
import com.gptr.benchmark.llm.JudgeClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * ③ KAE-lite：引用覆盖维度（对标 DeepResearch Arena 的 Keypoint-Aligned Evaluation）。
 *
 * <p>与 D2 互补：D2 从句子出发查"这句的引用是否真支持"（抓编造引用/张冠李戴）；
 * KAE 从引用出发查"报告是否把引用的关键事实写全/写对"——新增 <b>KOR（遗漏率）</b>
 * 量化"引了不用（充数）"，KCR 量化"自引矛盾"，KSR 量化覆盖。
 *
 * <p>协议（域内简化版）：
 * <ol>
 *   <li>报告自引 URL 集（正文前 40 句的内联引用，去重 ≤8 源）→ 抓正文（复用
 *       {@link SourceFetcher}）；抓取失败源不计入。</li>
 *   <li>每源 LLM 抽取事实关键点（≤6/源，正文截断 3k）；关键点总量 cap 48。</li>
 *   <li>跨源去重（规则级：归一化全等；语义去重未做——LLM 版留作后续，避免引入
 *       无审计的抽取-去重 LLM 链）。</li>
 *   <li>逐点四态判定（judge 只看报告正文）：covered / contradicted / omitted /
 *       <b>abstain</b>（Arena 的强制三态无弃权是缺陷，我们保留如实弃权桶）。</li>
 *   <li>指标：KSR=covered/n、KCR=contradicted/n、KOR=omitted/n、
 *       Efficiency=KSR/(KCR+KOR)（分母 0 → NaN）；abstain/剥离/批失败单列。</li>
 * </ol>
 */
public final class KeypointCoverage {

    private static final int MAX_URLS = 8;
    private static final int MAX_KEYPOINTS_PER_SOURCE = 6;
    private static final int MAX_KEYPOINTS = 48;
    private static final int VERDICT_BATCH = 15;

    private static final String EXTRACT_SYSTEM = """
            你是事实要点抽取器。从给定网页正文中抽取可作为事实核验的关键点：
            每条一句话、具体可验证（含数值/名称/时间/主张/定义），优先抽取与主体内容
            最相关的要点，最多 6 条。正文不可用或无可核验内容时输出空数组。
            只输出 JSON：{"keypoints":["<要点1>","<要点2>"]}
            """;

    private static final String VERDICT_SYSTEM = """
            你是报告覆盖度核查员。给定一份研究报告与一组【来自该报告引用来源】的事实关键点，
            对每个关键点判定报告正文是否覆盖该内容：
            - covered：报告正文明确写出该关键点的内容（数值/名称/主张相符，允许等价表述）
            - contradicted：报告正文与该关键点直接矛盾
            - omitted：报告未提及该关键点
            - abstain：关键点本身含糊，或无法从报告正文判断——如实弃权，不要猜测
            以关键点编号作答，只输出 JSON：
            {"results":[{"id":1,"verdict":"covered|contradicted|omitted|abstain"}]}
            """;

    /** KAE-lite 结果。Efficiency 分母为 0 时 = NaN（调用方输出 null/-1）。 */
    public record Result(int sources, int sourcesFetched, int fetchFailed,
                         int keypointsExtracted, int keypointsDeduped,
                         int covered, int contradicted, int omitted, int abstain,
                         int dropped, int batchesFailed,
                         double ksr, double kcr, double kor, double efficiency) {
    }

    private final SourceFetcher fetcher;

    public KeypointCoverage(String crawlerBase) {
        this.fetcher = new SourceFetcher(crawlerBase);
    }

    /** 抓取与关键点抽取的产物（原方法内联计数，拆出后以 record 传递）。 */
    private record Fetched(int sourcesFetched, int fetchFailed, List<String> keypoints) {
    }

    /** 逐批四态判定的计数（原方法内联计数器）。 */
    private record Tally(int covered, int contradicted, int omitted, int abstain,
                         int dropped, int batchesFailed) {
    }

    /** 判 KAE-lite；报告无内联引用 / 无可用源 / 无关键点 → null。 */
    public Result calculate(String report, JudgeClient judge) {
        ReportText.Split split = ReportText.split(report);
        List<ReportText.Sentence> sentences = ReportText.sentences(split.body(), 40);
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (ReportText.Sentence s : sentences) {
            urls.addAll(s.citationUrls());
        }
        List<String> targets = new ArrayList<>(urls);
        if (targets.isEmpty()) {
            return null;
        }
        if (targets.size() > MAX_URLS) {
            targets = targets.subList(0, MAX_URLS);
        }
        Map<String, String> cache = new HashMap<>();
        fetcher.fetchInto(targets, cache);

        Fetched fetched = fetchKeypoints(targets, cache, judge);
        if (fetched.sourcesFetched() == 0) {
            return null; // 引用源全部抓取失败：无判定基础
        }
        List<String> deduped = dedupe(fetched.keypoints());
        if (deduped.isEmpty()) {
            return null;
        }
        Tally t = tallyVerdicts(report, deduped, judge);

        int n = deduped.size();
        double kcr = n == 0 ? Double.NaN : (double) t.contradicted() / n;
        double kor = n == 0 ? Double.NaN : (double) t.omitted() / n;
        double ksr = n == 0 ? Double.NaN : (double) t.covered() / n;
        double denom = kcr + kor;
        return new Result(targets.size(), fetched.sourcesFetched(), fetched.fetchFailed(),
                fetched.keypoints().size(), n,
                t.covered(), t.contradicted(), t.omitted(), t.abstain(), t.dropped(),
                t.batchesFailed(),
                ksr, kcr, kor, denom == 0 ? Double.NaN : ksr / denom);
    }

    /** 抓取引用源正文并抽取关键点（原 calculate 内联段，逐字搬入）。 */
    private Fetched fetchKeypoints(List<String> targets, Map<String, String> cache,
                                   JudgeClient judge) {
        int sourcesFetched = 0;
        int fetchFailed = 0;
        List<String> keypoints = new ArrayList<>();
        for (String url : targets) {
            String content = cache.get(url);
            if (content == null || content.isBlank()) {
                fetchFailed++;
                continue;
            }
            sourcesFetched++;
            List<String> kp = extractKeypoints(url, content, judge);
            for (String k : kp) {
                if (keypoints.size() < MAX_KEYPOINTS) {
                    keypoints.add(k);
                }
            }
        }
        return new Fetched(sourcesFetched, fetchFailed, keypoints);
    }

    /** 逐批四态判定（原 calculate 内联段，逐字搬入）。id 回显校验：
     *  越界 / 重复 / 白名单外 verdict / judge 漏答，一律计 dropped（不可信输出）。 */
    private Tally tallyVerdicts(String report, List<String> deduped, JudgeClient judge) {
        int covered = 0;
        int contradicted = 0;
        int omitted = 0;
        int abstain = 0;
        int dropped = 0;
        int batchesFailed = 0;
        for (int start = 0; start < deduped.size(); start += VERDICT_BATCH) {
            List<String> batch = deduped.subList(start, Math.min(deduped.size(), start + VERDICT_BATCH));
            JsonNode root = judgeVerdicts(report, batch, judge);
            if (root == null || !root.path("results").isArray()) {
                batchesFailed++;
                continue;
            }
            int n = batch.size();
            boolean[] seen = new boolean[n];
            for (JsonNode v : root.path("results")) {
                JsonNode idNode = v.path("id");
                int id = idNode.isIntegralNumber() ? idNode.asInt() : -1;
                if (id < 1 || id > n || seen[id - 1]) {
                    dropped++; // 越界/重复 id：丢弃
                    continue;
                }
                seen[id - 1] = true;
                String verdict = v.path("verdict").asText("");
                switch (verdict) {
                    case "covered" -> covered++;
                    case "contradicted" -> contradicted++;
                    case "omitted" -> omitted++;
                    case "abstain" -> abstain++;
                    default -> dropped++; // 白名单外：丢弃
                }
            }
            // 未作答的关键点：计 dropped（judge 漏答 = 不可信输出）
            for (boolean b : seen) {
                if (!b) {
                    dropped++;
                }
            }
        }
        return new Tally(covered, contradicted, omitted, abstain, dropped, batchesFailed);
    }

    /** 单源 LLM 抽取关键点；失败/坏输出 → 空（该源贡献 0，不中断）。 */
    private List<String> extractKeypoints(String url, String content, JudgeClient judge) {
        String user = "来源 URL：" + url + "\n正文：\n" + ReportText.truncate(content, 3000);
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JsonNode root = judge.chatJson(EXTRACT_SYSTEM, user);
                if (root == null || !root.path("keypoints").isArray()) {
                    return List.of();
                }
                List<String> out = new ArrayList<>();
                for (JsonNode k : root.path("keypoints")) {
                    String s = k.asText("");
                    if (!s.isBlank()) {
                        out.add(s.trim());
                    }
                    if (out.size() >= MAX_KEYPOINTS_PER_SOURCE) {
                        break;
                    }
                }
                return out;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("keypoint extraction failed after 3 attempts: " + last, last);
    }

    /** 规则去重：归一化（去空白/标点/小写）后全等只保留第一条。 */
    static List<String> dedupe(List<String> keypoints) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String k : keypoints) {
            String norm = k == null ? "" : k.replaceAll("[\\s\\p{Punct}。，、；：！？「」『』（）【】]", "")
                    .toLowerCase();
            if (norm.isEmpty() || seen.contains(norm)) {
                continue;
            }
            seen.add(norm);
            out.add(k.trim());
        }
        return out;
    }

    private JsonNode judgeVerdicts(String report, List<String> batch, JudgeClient judge) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            sb.append(i + 1).append(". ").append(batch.get(i)).append("\n");
        }
        String user = "研究报告：\n" + ReportText.truncate(report, 10000)
                + "\n\n事实关键点（按编号作答）：\n" + sb;
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return judge.chatJson(VERDICT_SYSTEM, user);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("coverage verdict failed after 3 attempts: " + last, last);
    }
}
