package com.gptr.benchmark.stats;

/**
 * Wilson 95% 置信区间（比例指标用，如准确率；小样本比正态近似可靠）。
 */
public final class Wilson {

    private static final double Z = 1.959963984540054; // 95% 双侧

    private Wilson() {
    }

    /**
     * @param n      样本数
     * @param correct 成功数
     * @return {low, high}；n=0 返回 {0, 0}
     */
    public static double[] ci95(int n, int correct) {
        if (n <= 0) {
            return new double[]{0, 0};
        }
        double p = (double) correct / n;
        double z2 = Z * Z;
        double center = (p + z2 / (2 * n)) / (1 + z2 / n);
        double half = Z * Math.sqrt((p * (1 - p) + z2 / (4 * n)) / n) / (1 + z2 / n);
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }
}
