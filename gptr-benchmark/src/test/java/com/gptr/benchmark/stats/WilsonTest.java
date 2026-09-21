package com.gptr.benchmark.stats;

import com.gptr.benchmark.BenchmarkMain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wilson CI 单测（C3：仪器校准——公式边界）。 */
class WilsonTest {

    @Test
    void zeroSampleReturnsZero() {
        double[] ci = Wilson.ci95(0, 0);
        assertEquals(0, ci[0]);
        assertEquals(0, ci[1]);
    }

    @Test
    void perfectScoreHasNonZeroLowerBound() {
        // n=20 全对：下限应 ≈ 1/(1+z²/n)（正确率 1.0 时 CI 不为 [0,0]）
        double[] ci = Wilson.ci95(20, 20);
        assertEquals(1.0, ci[1], 1e-9);
        assertTrue(ci[0] > 0.8, "n=20 全对时下限应 ~0.84: " + ci[0]);
        assertTrue(ci[0] < 1.0);
    }

    @Test
    void smallSampleIntervalIsWide() {
        double[] ci = Wilson.ci95(4, 4);
        assertTrue(ci[1] - ci[0] > 0.4, "n=4 区间应宽");
    }

    @Test
    void halfRateCenterAroundHalf() {
        double[] ci = Wilson.ci95(100, 50);
        assertTrue(ci[0] < 0.5 && ci[1] > 0.5, "0.5 应在区间内: " + ci[0] + "," + ci[1]);
    }

    @Test
    void percentileLinearInterpolation() {
        // H1：对标 py run_eval 的 p50/p95（线性插值）
        assertEquals(5.0, BenchmarkMain.percentile(List.of(1.0, 3.0, 5.0, 7.0, 9.0), 0.50), 1e-9);
        assertEquals(8.6, BenchmarkMain.percentile(List.of(1.0, 3.0, 5.0, 7.0, 9.0), 0.95), 1e-9);
        assertEquals(1.0, BenchmarkMain.percentile(List.of(1.0, 3.0, 5.0), 0.0), 1e-9);
        assertEquals(5.0, BenchmarkMain.percentile(List.of(1.0, 3.0, 5.0), 1.0), 1e-9);
        assertEquals(3.0, BenchmarkMain.percentile(List.of(1.0, 3.0, 5.0), 0.50), 1e-9);
        // 空列表 → NaN
        assertTrue(Double.isNaN(BenchmarkMain.percentile(List.of(), 0.5)));
    }
}
