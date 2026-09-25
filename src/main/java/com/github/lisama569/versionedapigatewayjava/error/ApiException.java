package com.github.lisama569.versionedapigatewayjava.error;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final Long retryAfterMillis;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Long retryAfterMillis) {
        super(message);
        this.code = code;
        this.retryAfterMillis = retryAfterMillis;
    }

    public ErrorCode code() {
        return code;
    }

    public Long retryAfterMillis() {
        return retryAfterMillis;
    }
}
