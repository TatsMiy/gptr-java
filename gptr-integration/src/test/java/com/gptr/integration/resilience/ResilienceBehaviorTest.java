package com.gptr.integration.resilience;

import com.gptr.integration.client.mock.MockLlmClient;
import com.gptr.integration.client.mock.MockLlmClient.Mode;
import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试/熔断行为单元测试：瞬时错误重试恢复、永久错误不重试、配额耗尽、
 * 熔断打开保护。
 */
class ResilienceBehaviorTest {

    private static final Duration FAST = Duration.ofMillis(10);

    @Test
    void transientErrorsAreRetriedThenSucceed() {
        MockLlmClient llm = new MockLlmClient("llm", Mode.TRANSIENT_THEN_OK, 2);
        Supplier<String> protectedCall = ResilienceBeans.protectedSupplier(
                () -> llm.chat("sys", "user"),
                ResilienceBeans.retry("llm", 4, FAST, 2),
                ResilienceBeans.breaker("llm", 10, 50, Duration.ofSeconds(5)));

        String reply = protectedCall.get();
        assertTrue(reply.contains("recovered after 3 calls"),
                "expected recovery on 3rd attempt, got: " + reply);
    }

    @Test
    void permanentErrorsAreNotRetried() {
        MockLlmClient llm = new MockLlmClient("llm", Mode.PERMANENT);
        Supplier<String> protectedCall = ResilienceBeans.protectedSupplier(
                () -> llm.chat("sys", "user"),
                ResilienceBeans.retry("llm", 5, FAST, 2),
                ResilienceBeans.breaker("llm", 10, 50, Duration.ofSeconds(5)));

        assertThrows(PermanentApiException.class, protectedCall::get);
    }

    @Test
    void quotaExhaustedAfterRetries() {
        MockLlmClient llm = new MockLlmClient("llm", Mode.QUOTA);
        Supplier<String> protectedCall = ResilienceBeans.protectedSupplier(
                () -> llm.chat("sys", "user"),
                ResilienceBeans.retry("llm", 3, FAST, 2),
                ResilienceBeans.breaker("llm", 10, 50, Duration.ofSeconds(5)));

        assertThrows(QuotaApiException.class, protectedCall::get);
    }

    @Test
    void circuitBreakerOpensAfterRepeatedFailures() {
        var breaker = ResilienceBeans.breaker("llm", 4, 50, Duration.ofSeconds(10));
        MockLlmClient llm = new MockLlmClient("llm", Mode.QUOTA); // 每次都失败
        Supplier<String> protectedCall = ResilienceBeans.protectedSupplier(
                () -> llm.chat("sys", "user"),
                ResilienceBeans.retry("llm", 1, FAST, 1),
                breaker);

        // 4 次失败（窗口 4，阈值 50%）→ 熔断打开
        for (int i = 0; i < 4; i++) {
            assertThrows(TransientApiException.class, protectedCall::get);
        }
        assertTrue(breaker.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN,
                "breaker must be OPEN after 4 failures");

        // 熔断打开：后续调用立即抛 CallNotPermittedException（不触达 LLM）
        assertThrows(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class, protectedCall::get);
    }
}
