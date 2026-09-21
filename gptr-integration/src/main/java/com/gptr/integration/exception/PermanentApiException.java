package com.gptr.integration.exception;

/**
 * 永久性错误（400/401/403：key 失效、配置错误、校验失败）——不重试、不降级，
 * 立即失败并直达用户。
 */
public class PermanentApiException extends RuntimeException {

    private final String source;

    public PermanentApiException(String source, String message) {
        super(source + ": " + message);
        this.source = source;
    }

    public String getSource() {
        return source;
    }
}
