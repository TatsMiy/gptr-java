package com.gptr.integration.resilience;

import com.gptr.integration.client.mock.MockSearchClient;
import com.gptr.integration.client.mock.MockSearchClient.Mode;
import com.gptr.integration.exception.TransientApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级链单元测试：顺序成功 / 瞬时失败降级 / 熔断打开跳过 / 全部失败。
 */
class FallbackChainTest {

    @Test
    void firstStepSucceedsWithoutTouchingSecond() {
        AtomicInteger secondCalls = new AtomicInteger();
        List<FallbackChain.Step<List<String>>> steps = List.of(
                new FallbackChain.Step<>("tavily", () -> List.of("first")),
                new FallbackChain.Step<>("serper", () -> {
                    secondCalls.incrementAndGet();
                    return List.of("second");
                }));

        assertEquals(List.of("first"), FallbackChain.execute(steps));
        assertEquals(0, secondCalls.get(), "second step must not be called");
    }

    @Test
    void transientFailureFallsBackToNextStep() {
        List<FallbackChain.Step<List<String>>> steps = List.of(
                new FallbackChain.Step<>("tavily", () -> {
                    throw new TransientApiException("tavily", "boom");
                }),
                new FallbackChain.Step<>("serper", () -> List.of("serper-result")));

        assertEquals(List.of("serper-result"), FallbackChain.execute(steps));
    }

    @Test
    void openCircuitBreakerSkipsStep() {
        // 熔断器：窗口 2、阈值 50%，2 次失败打满进入 OPEN
        CircuitBreaker cb = ResilienceBeans.breaker("search-tavily", 2, 50, Duration.ofSeconds(10));
        AtomicInteger tavilyCalls = new AtomicInteger();
        Supplier<List<String>> tavily = () -> {
            tavilyCalls.incrementAndGet();
            throw new TransientApiException("tavily", "boom");
        };

        for (int i = 0; i < 2; i++) {
            assertThrows(TransientApiException.class, () -> cb.executeSupplier(tavily));
        }
        assertTrue(cb.getState() == CircuitBreaker.State.OPEN, "breaker must be OPEN");

        List<FallbackChain.Step<List<String>>> steps = List.of(
                new FallbackChain.Step<>("tavily",
                        ResilienceBeans.protectedSupplier(tavily,
                                ResilienceBeans.retry("search-tavily", 1, Duration.ofMillis(10), 1),
                                cb)),
                new FallbackChain.Step<>("serper", () -> List.of("serper-result")));

        assertEquals(List.of("serper-result"), FallbackChain.execute(steps));
        assertEquals(2, tavilyCalls.get(), "OPEN breaker must not invoke the supplier");
    }

    @Test
    void allStepsFailThrowsLastException() {
        List<FallbackChain.Step<List<String>>> steps = List.of(
                new FallbackChain.Step<>("tavily", () -> {
                    throw new TransientApiException("tavily", "a");
                }),
                new FallbackChain.Step<>("serper", () -> {
                    throw new TransientApiException("serper", "b");
                }));

        TransientApiException e = assertThrows(TransientApiException.class, () -> FallbackChain.execute(steps));
        assertEquals("serper", e.getSource());
    }

    @Test
    void emptyChainThrows() {
        assertThrows(IllegalStateException.class, () -> FallbackChain.execute(List.of()));
    }

    @Test
    void mockClientModes() {
        // mock 检索返回 SearchResponse（sourceUsed=自身名）
        var resp = new MockSearchClient("ok", Mode.OK).search("q");
        assertEquals(2, resp.results().size());
        assertEquals("ok", resp.sourceUsed());
        assertThrows(TransientApiException.class,
                () -> new MockSearchClient("t", Mode.TRANSIENT).search("q"));
        assertThrows(com.gptr.integration.exception.QuotaApiException.class,
                () -> new MockSearchClient("q", Mode.QUOTA).search("q"));
        assertThrows(com.gptr.integration.exception.PermanentApiException.class,
                () -> new MockSearchClient("p", Mode.PERMANENT).search("q"));
    }

    @Test
    void openBreakerShortCircuitsThroughFallback() {
        CircuitBreaker cb = ResilienceBeans.breaker("x", 1, 100, Duration.ofSeconds(60));
        cb.onError(1000, TimeUnit.MILLISECONDS, new RuntimeException("boom")); // 窗口 1 阈值 100% → OPEN
        assertTrue(cb.getState() == CircuitBreaker.State.OPEN, "breaker must be OPEN");

        AtomicInteger calls = new AtomicInteger();
        List<FallbackChain.Step<String>> steps = List.of(
                new FallbackChain.Step<>("x", ResilienceBeans.protectedSupplier(() -> {
                    calls.incrementAndGet();
                    return "never";
                }, ResilienceBeans.retry("x", 1, Duration.ofMillis(1), 1), cb)),
                new FallbackChain.Step<>("y", () -> "fallback-y"));

        assertEquals("fallback-y", FallbackChain.execute(steps));
        assertEquals(0, calls.get(), "OPEN breaker must short-circuit the step");

        // 直接调用被熔断保护的 supplier → CallNotPermittedException
        assertThrows(CallNotPermittedException.class,
                ResilienceBeans.protectedSupplier(() -> "never",
                        ResilienceBeans.retry("x", 1, Duration.ofMillis(1), 1), cb)::get);
    }
}
