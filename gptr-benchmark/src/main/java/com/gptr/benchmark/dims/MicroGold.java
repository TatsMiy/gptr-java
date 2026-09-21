package com.gptr.benchmark.dims;

import com.fasterxml.jackson.databind.JsonNode;
import com.gptr.benchmark.llm.JudgeClient;

import java.util.ArrayList;
import java.util.List;

/**
 * ② 评测器的评测：micro-gold 探针（对标 DeepFact ACL-26 的 Micro-Gold Protocol）。
 *
 * <p>DeepFact 实证：把已知答案句混入标注流（1:4 supported:unsupported、占 25%）后，
 * 在自己领域内且正确激励的 PhD 专家准确率也只有 60.8%——"先测你的标注者/judge，
 * 再信它的分"。本探针把同一思想用于我们的 LLM judge：
 * <ul>
 *   <li><b>语料夹具</b>：6 篇自包含事实语料（数值/主体/限定词/时间均可机器锚定）。</li>
 *   <li><b>对抗派生</b>（错误分类学注入，对应 DeepFact 表 8 的典型错误码）：
 *       主体/来源替换（attribution swap）、数值篡改（单位/数量级偷换）、
 *       正反颠倒、限定条件塌缩（"之一"→"唯一"/不确定措辞→绝对断言）→ 期望
 *       contradictory；语料外的无关事实 → 期望 inconclusive（语料未提及=如实弃权）。
 *       支持:非支持 ≈ 1:4（对齐 DeepFact）。</li>
 *   <li><b>判定</b>：复用 {@link HallucinationJudge#SYSTEM} 三态协议（同 prompt 防漂移），
 *       分批跑 judge，逐句对比期望。</li>
 * </ul>
 * 关键输出：judge 总准确率、supported/contradictory/inconclusive 各自的检出率，
 * 以及<b>幻觉漏检率</b>（期望 contradictory 被判成 supported——最危险的误判）。
 * 每个 fixture 句子的期望标签由构造保证（篡改点都在语料内有明确锚），无需人工标注。
 */
public final class MicroGold {

    /** 单条探针：句子 + 其"引用来源"语料（判定限定在语料内）+ 期望三态判定。 */
    public record ProbeCase(String sentence, String corpus, String expected) {
    }

    private MicroGold() {
    }

    /** 语料库根 URL（探针不真抓取；URL 仅作引用形态占位）。 */
    private static final String DOC = "https://micro-gold.example/doc";

    /** 生成语料块（与 D2 语料形态一致：[来源 url] + 正文）。 */
    private static String corpus(int doc, String body) {
        return "[来源 " + DOC + doc + "]\n" + body;
    }

    /**
     * 固定夹具（6 组事实语料 × 派生句）。派生句期望由构造保证：每个篡改点在语料内
     * 有明确锚（原主体/原数值/限定词/正反），judge 依语料即可判定，无需外部知识。
     */
    public static List<ProbeCase> fixtures() {
        List<ProbeCase> out = new ArrayList<>();

        // doc1：公司收购（主体 + 数值 + 限定词"之一"）
        String c1 = corpus(1, "2024年3月，星云科技以42亿元收购了蓝湖数据公司80%的股权，"
                + "交易完成后星云科技成为蓝湖数据的控股股东。"
                + "此次收购被认为是人工智能数据服务领域的重要交易之一。");
        out.add(new ProbeCase("星云科技于2024年3月以42亿元收购蓝湖数据80%的股权。", c1, "supported"));
        out.add(new ProbeCase("星云科技于2024年3月以42亿元收购了青云数据公司80%的股权。", c1, "contradictory"));
        out.add(new ProbeCase("星云科技于2024年3月以24亿元收购蓝湖数据80%的股权。", c1, "contradictory"));
        out.add(new ProbeCase("此次收购被认为是人工智能数据服务领域唯一的重要交易。", c1, "contradictory"));
        out.add(new ProbeCase("收购完成后，蓝湖数据的创始人仍担任公司首席执行官。", c1, "inconclusive"));

        // doc2：研究 attribution swap（团队-结论错位）
        String c2 = corpus(2, "在一项发表于《自然·通讯》的研究中，张伟团队发现每日饮用两杯以上绿茶"
                + "与降低心血管疾病风险相关。同一期刊的另一项研究由李娜团队完成，"
                + "结论是每日饮用咖啡与睡眠质量无显著关联。");
        out.add(new ProbeCase("张伟团队在《自然·通讯》的研究中发现每日饮用两杯以上绿茶"
                + "与降低心血管疾病风险相关。", c2, "supported"));
        out.add(new ProbeCase("李娜团队在《自然·通讯》的研究中发现每日饮用两杯以上绿茶"
                + "与降低心血管疾病风险相关。", c2, "contradictory"));
        out.add(new ProbeCase("张伟团队发现每日饮用五杯以上绿茶与降低心血管疾病风险相关。", c2, "contradictory"));
        out.add(new ProbeCase("张伟团队的研究表明饮用绿茶与心血管疾病风险无关。", c2, "contradictory"));
        out.add(new ProbeCase("李娜团队的咖啡研究样本量超过一万人。", c2, "inconclusive"));

        // doc3：珠峰高程（数值 + 公布主体 + 新旧值）
        String c3 = corpus(3, "珠穆朗玛峰的最新官方高程为8848.86米，这一数据由中尼两国于2020年12月"
                + "联合公布，取代了此前使用的8844.43米。");
        out.add(new ProbeCase("珠穆朗玛峰的官方高程为8848.86米，由中尼两国于2020年12月联合公布。",
                c3, "supported"));
        out.add(new ProbeCase("珠穆朗玛峰官方高程为8848.43米。", c3, "contradictory"));
        out.add(new ProbeCase("珠穆朗玛峰的最新官方高程由中美两国于2020年12月联合公布。", c3, "contradictory"));
        out.add(new ProbeCase("珠穆朗玛峰此前使用的8844.43米仍是官方高程。", c3, "contradictory"));
        out.add(new ProbeCase("珠峰地区的冰川面积在过去十年缩小了百分之二十。", c3, "inconclusive"));

        // doc4：出口数据（数量级 + 市场主体 + 正反）
        String c4 = corpus(4, "报告显示，2023年中国新能源汽车出口量为120万辆，同比增长77.6%。"
                + "其中，对欧洲市场的出口占比约为38%。");
        out.add(new ProbeCase("2023年中国新能源汽车出口量120万辆，同比增长77.6%。", c4, "supported"));
        out.add(new ProbeCase("2023年中国新能源汽车出口量为1200万辆，同比增长77.6%。", c4, "contradictory"));
        out.add(new ProbeCase("2023年中国新能源汽车出口量120万辆，其中对北美市场出口占比约38%。",
                c4, "contradictory"));
        out.add(new ProbeCase("2023年中国新能源汽车出口同比下降77.6%。", c4, "contradictory"));
        out.add(new ProbeCase("2024年上半年中国新能源汽车出口量超过100万辆。", c4, "inconclusive"));

        // doc5：奖项（主体 + 年度 + "仅颁发一次"限定）
        String c5 = corpus(5, "2025年度图灵奖在2026年4月揭晓，授予分布式系统领域的奠基者"
                + "艾丽斯·格林教授。该奖项每年度仅颁发一次。");
        out.add(new ProbeCase("2025年度图灵奖于2026年4月揭晓，授予了艾丽斯·格林教授。", c5, "supported"));
        out.add(new ProbeCase("2025年度图灵奖于2026年4月揭晓，授予了罗伯特·李教授。", c5, "contradictory"));
        out.add(new ProbeCase("2024年度图灵奖于2026年4月揭晓，授予了艾丽斯·格林教授。", c5, "contradictory"));
        out.add(new ProbeCase("该奖项在2026年4月之后又于同年度颁发了一次。", c5, "contradictory"));
        out.add(new ProbeCase("艾丽斯·格林教授目前担任斯坦福大学计算机系主任。", c5, "inconclusive"));

        // doc6：科学结论不确定性（绝对化断言与语料"可能/证据不足"冲突）
        String c6 = corpus(6, "初步研究提示，间歇性禁食可能有助于改善2型糖尿病患者的血糖控制，"
                + "但现有证据主要来自小样本短期试验，结论仍不明确。");
        out.add(new ProbeCase("初步研究提示间歇性禁食可能有助于改善2型糖尿病患者的血糖控制。",
                c6, "supported"));
        out.add(new ProbeCase("间歇性禁食有助于改善2型糖尿病患者的血糖控制。", c6, "contradictory"));
        out.add(new ProbeCase("初步研究提示间歇性禁食可能有助于改善1型糖尿病患者的血糖控制。",
                c6, "contradictory"));
        out.add(new ProbeCase("间歇性禁食已被证明可延长实验小鼠的寿命。", c6, "inconclusive"));
        out.add(new ProbeCase("间歇性禁食会影响运动员的肌肉合成速率。", c6, "inconclusive"));

        return out;
    }

    /** 探针报告：judge 三态自检结果。 */
    public record Report(int total, int correct, double accuracy,
                         int supportedExpected, int supportedCaught,
                         int contradictionExpected, int contradictionCaught,
                         int inconclusiveExpected, int inconclusiveCaught,
                         int dropped, int batchesFailed) {

        /** 幻觉漏检率：期望 contradictory 却被判 supported 的比例（最危险误判）。 */
        public double hallucinationMissRate() {
            int missed = contradictionExpected - contradictionCaught;
            return contradictionExpected == 0 ? Double.NaN : (double) missed / contradictionExpected;
        }
    }

    /** 跑一次自检（batchSize=批大小，同 D2 协议分批）。 */
    public static Report run(JudgeClient judge, int batchSize) {
        List<ProbeCase> cases = fixtures();
        int correct = 0;
        int sExp = 0;
        int sCaught = 0;
        int cExp = 0;
        int cCaught = 0;
        int iExp = 0;
        int iCaught = 0;
        int dropped = 0;
        int batchesFailed = 0;
        for (int start = 0; start < cases.size(); start += batchSize) {
            List<ProbeCase> batch = cases.subList(start, Math.min(cases.size(), start + batchSize));
            JsonNode root = judgeBatch(batch, judge);
            if (root == null || !root.path("verdicts").isArray()) {
                batchesFailed++;
                continue;
            }
            // 逐条匹配（sentence 归一化回显校验，同 D2 防注水）
            List<String> normalized = batch.stream()
                    .map(c -> HallucinationJudge.normalize(c.sentence())).toList();
            for (JsonNode v : root.path("verdicts")) {
                String sentence = v.path("sentence").asText("");
                int idx = sentence.isBlank() ? -1 : normalized.indexOf(HallucinationJudge.normalize(sentence));
                if (idx < 0) {
                    dropped++;
                    continue;
                }
                String verdict = v.path("verdict").asText("");
                if (!"supported".equals(verdict) && !"contradictory".equals(verdict)
                        && !"inconclusive".equals(verdict)) {
                    dropped++;
                    continue;
                }
                ProbeCase c = batch.get(idx);
                if (verdict.equals(c.expected())) {
                    correct++;
                }
                if ("supported".equals(c.expected())) {
                    sExp++;
                    if ("supported".equals(verdict)) {
                        sCaught++;
                    }
                } else if ("contradictory".equals(c.expected())) {
                    cExp++;
                    if ("contradictory".equals(verdict)) {
                        cCaught++;
                    }
                } else {
                    iExp++;
                    if ("inconclusive".equals(verdict)) {
                        iCaught++;
                    }
                }
            }
        }
        int total = cases.size();
        return new Report(total, correct, total == 0 ? -1 : (double) correct / total,
                sExp, sCaught, cExp, cCaught, iExp, iCaught, dropped, batchesFailed);
    }

    private static JsonNode judgeBatch(List<ProbeCase> batch, JudgeClient judge) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            ProbeCase c = batch.get(i);
            sb.append("【句子").append(i + 1).append("】").append(c.sentence()).append("\n")
                    .append("该句引用来源正文：\n").append(ReportText.truncate(c.corpus(), 4000))
                    .append("\n\n");
        }
        String user = "逐句判定（每句只用它自己的引用来源）：\n" + sb;
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return judge.chatJson(HallucinationJudge.SYSTEM, user);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("judge failed after 3 attempts: " + last, last);
    }

    // ------------------------------------------------------------------
    // D3（AnswerAccuracyJudge）判定自检——否定/转折极性陷阱必须有护栏。
    // 复用 AnswerAccuracyJudge.VERDICT_SYSTEM（同 prompt 防漂移）。
    // ------------------------------------------------------------------

    /** D3 探针：gold/superseded/候选 → 期望三分类 verdict。 */
    public record D3Case(String gold, String superseded, String candidate, String expected) {
    }

    /** 固定 D3 夹具：等价表述 / stale / 否定陷阱 / 转折陷阱 / 无关 / 缺实体。 */
    public static List<D3Case> d3Fixtures() {
        String goldTuring = "2025年ACM图灵奖授予 Charles H. Bennett 与 Gilles Brassard（量子信息奠基）";
        String supTuring = "Andrew G. Barto 与 Richard S. Sutton（2024年图灵奖，强化学习）";
        String goldEverest = "珠穆朗玛峰官方最新高程为 8848.86 米（2020年中尼联合公布）";
        String supEverest = "8844.43 米（2005年岩石面高程，旧官方值）";
        return List.of(
                new D3Case(goldTuring, supTuring,
                        "2025 年 ACM 图灵奖颁发给了 Charles H. Bennett 和 Gilles Brassard。", "correct"),
                new D3Case(goldTuring, supTuring,
                        "2025 年 ACM 图灵奖并未授予 Charles H. Bennett 与 Gilles Brassard。", "incorrect"),
                new D3Case(goldTuring, supTuring,
                        "坊间传闻获奖者是 Charles Bennett，但根据最新公布名单并非如此。", "incorrect"),
                new D3Case(goldEverest, supEverest,
                        "珠穆朗玛峰的最新官方高程为 8844.43 米。", "stale"),
                new D3Case(goldEverest, supEverest,
                        "珠穆朗玛峰海拔约 8848.86 米，来自 2020 年联合测量。", "correct"),
                new D3Case(goldEverest, supEverest,
                        "珠穆朗玛峰位于喜马拉雅山脉，是地球最高峰之一。", "incorrect"));
    }

    /** D3 自检结果。 */
    public record D3Report(int total, int correct, double accuracy,
                           int dropped, int batchesFailed) {
    }

    /** 跑 D3 自检（每例单判：gold/superseded/candidate 三元组 → verdict）。 */
    public static D3Report runD3(JudgeClient judge) {
        List<D3Case> cases = d3Fixtures();
        int correct = 0;
        int dropped = 0;
        int batchesFailed = 0;
        for (D3Case c : cases) {
            String user = "标准答案（gold）：%s\n\n过时答案白名单（superseded）：%s\n\n候选答案（reportedAnswer）：%s"
                    .formatted(c.gold(), c.superseded(), c.candidate());
            JsonNode root = null;
            Exception last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    root = judge.chatJson(AnswerAccuracyJudge.VERDICT_SYSTEM, user);
                    break;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (root == null || !root.path("verdict").isTextual()) {
                batchesFailed++;
                continue;
            }
            String verdict = root.path("verdict").asText();
            if (!"correct".equals(verdict) && !"stale".equals(verdict) && !"incorrect".equals(verdict)) {
                dropped++;
                continue;
            }
            if (verdict.equals(c.expected())) {
                correct++;
            }
        }
        int total = cases.size();
        return new D3Report(total, correct, total == 0 ? -1 : (double) correct / total,
                dropped, batchesFailed);
    }
}
