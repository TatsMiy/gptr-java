package com.gptr.integration.client.mock;

import com.gptr.integration.client.SearchClient;
import com.gptr.integration.client.SearchResponse;
import com.gptr.integration.client.SearchResult;
import com.gptr.integration.exception.PermanentApiException;
import com.gptr.integration.exception.QuotaApiException;
import com.gptr.integration.exception.TransientApiException;

import java.util.List;

/**
 * 模拟检索客户端。
 *
 * <p>行为模式（{@code mode}）：
 * <ul>
 *   <li>{@code ok}：返回固定假结果</li>
 *   <li>{@code transient}：每次抛 {@link TransientApiException}（触发重试/降级）</li>
 *   <li>{@code quota}：每次抛 {@link QuotaApiException}</li>
 *   <li>{@code permanent}：抛 {@link PermanentApiException}</li>
 * </ul>
 */
public class MockSearchClient implements SearchClient {

    public enum Mode { OK, TRANSIENT, QUOTA, PERMANENT }

    private final String name;
    private final Mode mode;
    private final int failTimes;   // TRANSIENT 模式下前 N 次失败（重试恢复场景）

    public MockSearchClient(String name, Mode mode) {
        this(name, mode, 0);
    }

    public MockSearchClient(String name, Mode mode, int failTimes) {
        this.name = name;
        this.mode = mode;
        this.failTimes = failTimes;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public SearchResponse search(String query) {
        switch (mode) {
            case TRANSIENT -> {
                if (failTimes > 0) {
                    throw new TransientApiException(name, "simulated transient failure (" + failTimes + " left)");
                }
                throw new TransientApiException(name, "simulated transient failure");
            }
            case QUOTA -> throw new QuotaApiException(name, "simulated quota exhausted");
            case PERMANENT -> throw new PermanentApiException(name, "simulated permanent error (bad key)");
            case OK -> {
                return new SearchResponse(List.of(
                        new SearchResult(name + " result 1", "https://example.com/" + name + "/1",
                                "mock snippet 1 for " + query),
                        new SearchResult(name + " result 2", "https://example.com/" + name + "/2",
                                "mock snippet 2 for " + query)), name);
            }
            default -> throw new IllegalStateException("unknown mode " + mode);
        }
    }
}
