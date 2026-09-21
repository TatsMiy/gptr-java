package com.gptr.benchmark;

import com.gptr.benchmark.dims.KeypointCoverage;
import com.gptr.benchmark.llm.JudgeClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * KAE-lite 探针（默认跳过）：量化"引了不用"（KOR）——对同一题的两份报告分别计算
 * 引用覆盖深度，用于回答"java 引用多但承载信息少"是否成立。
 *
  * <p>背景：KAE-lite（关键点引用覆盖）从引用出发查"报告是否把引用的关键事实写全"，
 * KOR=omitted/n 即遗漏率，正是 D1/D2 都测不到的盲区（引了真来源但只用一句带过）。
 *
 * <p>运行：
 * <pre>
 * mvn -o test -pl gptr-benchmark -Dtest=KeypointCoverageProbeTest -Dprobe.on=true \
 *     -Dprobe.dir=<对比集目录> -Dprobe.files=q08-java.md,q08-py.md
 * </pre>
 * 需 DEEPSEEK_API_KEY（judge）与 crawler（抓引用源正文）。
 */
class KeypointCoverageProbeTest {

    @Test
    void probe() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("probe.on")),
                "探针默认跳过（-Dprobe.on=true 启用）");
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "缺 DEEPSEEK_API_KEY");

        Path dir = Path.of(System.getProperty("probe.dir", "compare-set/round3"));
        String files = System.getProperty("probe.files", "q08-java.md,q08-py.md");
        String crawler = System.getProperty("probe.crawler", "http://localhost:8000");
        String judgeBase = System.getProperty("probe.judge-base", "https://api.deepseek.com");
        String judgeModel = System.getProperty("probe.judge-model", "deepseek-chat");

        KeypointCoverage kae = new KeypointCoverage(crawler);
        JudgeClient judge = new JudgeClient(judgeBase, judgeModel, key);

        for (String f : files.split(",")) {
            String name = f.trim();
            Path p = dir.resolve(name);
            if (!Files.exists(p)) {
                System.out.printf("%n===== %s =====%n  文件不存在: %s%n", name, p);
                continue;
            }
            String report = Files.readString(p);
            KeypointCoverage.Result r = kae.calculate(report, judge);
            System.out.printf("%n===== %s (报告 %d 字符) =====%n", name, report.length());
            if (r == null) {
                System.out.println("  null：无内联引用 / 引用源全部抓取失败 / 无关键点");
                continue;
            }
            System.out.printf("  源: 取样 %d, 抓成功 %d, 抓失败 %d%n",
                    r.sources(), r.sourcesFetched(), r.fetchFailed());
            System.out.printf("  关键点: 抽取 %d, 去重后 %d%n",
                    r.keypointsExtracted(), r.keypointsDeduped());
            System.out.printf("  四态: covered=%d contradicted=%d omitted=%d abstain=%d"
                            + " | dropped=%d batchFailed=%d%n",
                    r.covered(), r.contradicted(), r.omitted(), r.abstain(),
                    r.dropped(), r.batchesFailed());
            System.out.printf("  KSR=%.3f  KCR=%.3f  KOR=%.3f  Efficiency=%.3f%n",
                    r.ksr(), r.kcr(), r.kor(), r.efficiency());
        }
    }
}
