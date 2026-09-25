package com.github.lisama569.versionedapigatewayjava.common.error;

/**
 * 对外暴露的归一化错误码。原始上游异常类型绝不会出现在这里。
 */
public enum GatewayErrorCode {
    VERSION_UNSUPPORTED(400),
    MISSING_REQUIRED_FIELD(400),
    DEPRECATED_FIELD(400),
    INVALID_FIELD_VALUE(400),
    RATE_LIMITED_TRANSIENT(429),
    QUOTA_EXHAUSTED(429),
    UPSTREAM_TIMEOUT(504),
    UPSTREAM_ERROR(502),
    UPSTREAM_MALFORMED(502),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    GatewayErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
