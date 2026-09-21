package com.gptr.integration.resilience;

import java.util.List;
import java.util.function.Supplier;

/**
 * 通用降级链：按顺序尝试各步骤，前一个失败（含熔断打开
 * 抛出的 CallNotPermittedException）自动切下一个；全部失败抛最后一个异常。
 *
 * <p>每个步骤的 supplier 已由调用方包装好重试与熔断
 * （见 {@code ResilienceBeans#protectedSupplier}），本类只负责编排顺序。
 */
public final class FallbackChain {

    private FallbackChain() {
        // 工具类
    }

    /** 按顺序执行步骤，返回第一个成功结果；全部失败抛最后异常。 */
    public static <T> T execute(List<Step<T>> steps) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("fallback chain is empty");
        }
        RuntimeException last = null;
        for (Step<T> step : steps) {
            try {
                return step.supplier().get();
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    /** 降级链中的一个步骤（name 用于日志/指标标识）。 */
    public record Step<T>(String name, Supplier<T> supplier) {
    }
}
