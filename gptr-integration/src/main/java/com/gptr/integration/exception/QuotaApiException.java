package com.gptr.integration.exception;

/**
 * 配额耗尽（429 且 Retry-After 较长，或配额被持续打满）。
 *
 * <p>属瞬时类但语义独立（QUOTA）：重试退避拉长，若降级链无可用源则任务以
 * QUOTA_EXHAUSTED 失败。
 */
public class QuotaApiException extends TransientApiException {

    public QuotaApiException(String source, String message) {
        super(source, message);
    }
}
