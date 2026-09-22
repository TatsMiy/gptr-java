package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.JsonNode;
import com.gptr.benchmark.dataset.BenchmarkItem;
import com.gptr.benchmark.llm.JudgeClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * D2 幻觉率（句子-引用源绑定；三态判定 + 反证判据）。
 *
 * <p>核心修正（相对 P1 初版）：
 * <ol>
 *   <li><b>句子与引用源绑定</b>：每句只与<b>它自己内联引用</b>的 URL 正文比对，
 *       不再用"References 前 5 源混拼语料"判所有句子（原方法测的是语料覆盖率）。</li>
 *   <li><b>分类统计</b>：无引用句 → {@code noCitation}（单独统计，不进幻觉率分母）；
 *       引用源全部抓取失败 → {@code unverifiable}（单独统计）。</li>
 *   <li><b>三态判定（对标 DeepFact ACL-26）</b>：verdict ∈
 *       supported / contradictory / inconclusive。supported 要求引用正文直接支撑句子
 *       每个事实元素<b>且绑定语料内无反证</b>（DeepFact Supported 判据 iv 的域内化——
 *       多引用句子的全部引用正文互相矛盾即可判 contradictory，不做开放网络反证搜索，
 *       成本不变）；正文不足/模糊/未提及关键细节 → inconclusive（如实弃权，不猜测）。
 *       每条输出 evidence（支撑/矛盾的来源与片段），判定可审计。</li>
 *   <li><b>verdict 校验</b>：judge 返回的 {@code sentence} 必须与输入句匹配（归一化），
 *       verdicts 数不超过批大小、verdict 值必须在三态白名单——杜绝模型注水分母
 *       （曾实测 37 > 上限 20）。</li>
 *   <li><b>批失败重试</b>：单批 judge 失败重试 2 次后仍失败则该批丢弃并计数。</li>
 * </ol>
 *
 * <p>指标口径：hallucinationRate = contradictory / judged（确认矛盾的保守下限，
 * 不再把"语料未覆盖"当幻觉）；uncertainRate = (contradictory+inconclusive) / judged
 * （含"无法证实"的上限）。统计字段改名：unsupported → contradictory/inconclusive 拆分。
 */
public final class HallucinationJudge {

    private static final int MAX_SENTENCES = 20;
    private static final int BATCH = 5;

    /** 三态判定协议（包可见：MicroGold 自检与消融复用同一协议，防 prompt 漂移）。 */
    static final String SYSTEM = """
            你是事实核查员。给定若干"报告句子"以及每条句子【自己引用来源】的网页正文，
            对每句输出三态判定（严格只用该句自己的引用来源正文，不得用你自己的知识补全，
            不得跨句/跨来源推理）：
            - supported：句子的每个事实元素都被引用来源正文直接支撑，且该句全部引用正文
              中不存在反驳句子任一部分的内容（通读该句所有引用来源，来源间互相矛盾也算）。
            - contradictory：至少一处引用来源正文与句子主张直接矛盾
              （张冠李戴、数值/时间/主体不符、正反颠倒等）。
            - inconclusive：正文未提及关键细节、仅有部分支撑、或正文过短/模糊，
              无法直接支持也无法确认矛盾——如实标注 inconclusive，不要猜测。
            每条输出 evidence：supported/contradictory 给出支撑或矛盾的来源 URL 与
            关键原文片段（供审计）；inconclusive 描述缺失的关键内容。
            只输出 JSON：{"verdicts":[{"sentence":"报告原句（逐字）",
            "verdict":"supported|contradictory|inconclusive","evidence":"..."}]}
            """;

    private final SourceFetcher fetcher;

    public HallucinationJudge(String crawlerBase) {
        this.fetcher = new SourceFetcher(crawlerBase);
    }

    /** D2 判分结果（三态口径）：rate = contradictory / judged（保守下限；
     *  judged=0 → -1）。uncertainRate = (contradictory+inconclusive) / judged。 */
    public record Verdict(int judged, int contradictory, int inconclusive,
                          int noCitation, int unverifiable, int dropped,
                          int batchesFailed, double hallucinationRate, double uncertainRate) {
    }

    /** 判定输入：句子 + 其引用源的抓取语料。 */
    private record Judgable(ReportText.Sentence sentence, String corpus) {
    }

    public Verdict judge(BenchmarkItem item, String report, JudgeClient judge) {
        ReportText.Split split = ReportText.split(report);
        List<ReportText.Sentence> sentences = ReportText.sentences(split.body(), MAX_SENTENCES);
        if (sentences.isEmpty()) {
            return null;
        }
        Map<String, String> corpusCache = new HashMap<>(); // url → 抓取正文（失败=空串）
        int noCitation = 0;
        int unverifiable = 0;
        // 分类：无引用句 / 引用源全失败的句子 单独统计；可判句带各自语料合批
        List<Judgable> judgable = new ArrayList<>();
        for (ReportText.Sentence s : sentences) {
            if (s.citationUrls().isEmpty()) {
                noCitation++;
                continue;
            }
            fetcher.fetchInto(s.citationUrls(), corpusCache);
            String corpus = SourceFetcher.corpusFor(s.citationUrls(), corpusCache);
            if (corpus == null) {
                unverifiable++;
            } else {
                judgable.add(new Judgable(s, corpus));
            }
        }
        if (judgable.isEmpty()) {
            return new Verdict(0, 0, 0, noCitation, unverifiable, 0, 0, -1, -1);
        }

        int judged = 0;
        int contradictory = 0;
        int inconclusive = 0;
        int dropped = 0;
        int batchesFailed = 0;
        for (int i = 0; i < judgable.size(); i += BATCH) {
            List<Judgable> batch = judgable.subList(i, Math.min(judgable.size(), i + BATCH));
            List<ReportText.Sentence> batchSentences = batch.stream().map(Judgable::sentence).toList();
            JsonNode root = judgeWithRetry(batch, judge);
            if (root == null || !root.path("verdicts").isArray()) {
                batchesFailed++;
                continue;
            }
            BatchStats stats = matchVerdicts(batchSentences, root.path("verdicts"));
            judged += stats.judged;
            contradictory += stats.contradictory;
            inconclusive += stats.inconclusive;
            dropped += stats.dropped;
        }
        if (judged == 0) {
            return new Verdict(0, 0, 0, noCitation, unverifiable, dropped, batchesFailed, -1, -1);
        }
        return new Verdict(judged, contradictory, inconclusive, noCitation, unverifiable, dropped,
                batchesFailed, (double) contradictory / judged,
                (double) (contradictory + inconclusive) / judged);
    }

    /** 每批 prompt：逐句给出"句子 + 其引用来源正文"配对。 */
    private JsonNode judgeWithRetry(List<Judgable> batch, JudgeClient judge) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            Judgable j = batch.get(i);
            sb.append("【句子").append(i + 1).append("】").append(j.sentence().text()).append("\n")
                    .append("该句引用来源正文：\n").append(ReportText.truncate(j.corpus(), 4000))
                    .append("\n\n");
        }
        String user = "逐句判定（每句只用它自己的引用来源）：\n" + sb;
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return judge.chatJson(SYSTEM, user);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("judge failed after 3 attempts: " + last, last);
    }

    /**
     * 校验 judge 输出（纯函数，可单测）：verdicts 逐条按 {@code sentence} 与输入句
     * 匹配（归一化后）；verdict 值必须在三态白名单；不匹配/坏值丢弃并计数；
     * verdicts 超批大小只取前 N。
     */
    public record BatchStats(int judged, int contradictory, int inconclusive, int dropped) {
    }

    static BatchStats matchVerdicts(List<ReportText.Sentence> inputs, JsonNode verdicts) {
        List<String> normalized = new ArrayList<>();
        for (ReportText.Sentence s : inputs) {
            normalized.add(normalize(s.text()));
        }
        int judged = 0;
        int contradictory = 0;
        int inconclusive = 0;
        int dropped = 0;
        int limit = inputs.size();
        for (JsonNode v : verdicts) {
            if (limit <= 0) {
                dropped++; // 超出输入句数的多余输出：丢弃计数（防注水分母）
                continue;
            }
            String sentence = v.path("sentence").asText("");
            if (sentence.isBlank() || !normalized.contains(normalize(sentence))) {
                dropped++; // 非输入句/重复：丢弃
                continue;
            }
            String verdict = v.path("verdict").asText("");
            if (!"supported".equals(verdict) && !"contradictory".equals(verdict)
                    && !"inconclusive".equals(verdict)) {
                dropped++; // 白名单外值（含旧版 supported:true 形态）：丢弃防注水
                continue;
            }
            judged++;
            limit--;
            if ("contradictory".equals(verdict)) {
                contradictory++;
            } else if ("inconclusive".equals(verdict)) {
                inconclusive++;
            }
        }
        return new BatchStats(judged, contradictory, inconclusive, dropped);
    }

    /** 归一化用于回显校验：去空白与标点差异。 */
    static String normalize(String s) {
        return s.replaceAll("[\\s\\p{Punct}。，、；：！？「」『』（）【】]", "").toLowerCase();
    }
}
