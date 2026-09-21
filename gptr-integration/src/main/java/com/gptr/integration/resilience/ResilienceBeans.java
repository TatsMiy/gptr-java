package com.gptr.integration.resilience;

import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.TransientApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Resilience4j 重试/熔断工厂（编程式，测试可直接构建；生产由装配层按源构建）。
 *
 * <ul>
 *   <li>重试：指数退避 + 抖动，仅重试 {@link TransientApiException}（含 Quota），
 *       {@link PermanentApiException} 不重试</li>
 *   <li>熔断：按外部源分组（llm / search-tavily / search-serper / ...）</li>
 * </ul>
 */
public final class ResilienceBeans {

    private ResilienceBeans() {
        // 工具类
    }

    /** 指数退避重试：maxAttempts 次，初始 wait，倍增 multiplier。 */
    public static Retry retry(String name, int maxAttempts, Duration initialWait, double multiplier) {
        IntervalFunction interval = IntervalFunction.ofExponentialBackoff(
                initialWait.toMillis(), multiplier);
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(interval)
                .retryExceptions(TransientApiException.class)
                .ignoreExceptions(PermanentApiException.class)
                .build();
        return Retry.of(name, config);
    }

    /** 熔断：滑动窗口 window 次调用，失败率超 threshold% 打开，open 时长后半开。 */
    public static CircuitBreaker breaker(String name, int window, float thresholdPercent, Duration open) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(window)
                .failureRateThreshold(thresholdPercent)
                .waitDurationInOpenState(open)
                .ignoreExceptions(PermanentApiException.class)
                .build();
        return CircuitBreaker.of(name, config);
    }

    /** 把原始调用包装为"重试 + 熔断"保护后的 supplier（供降级链步骤使用）。 */
    public static <T> Supplier<T> protectedSupplier(Supplier<T> raw, Retry retry, CircuitBreaker breaker) {
        Supplier<T> withRetry = Retry.decorateSupplier(retry, raw);
        return CircuitBreaker.decorateSupplier(breaker, withRetry);
    }
}
