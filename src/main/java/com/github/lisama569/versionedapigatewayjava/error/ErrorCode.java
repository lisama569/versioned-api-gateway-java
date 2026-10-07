package com.github.lisama569.versionedapigatewayjava.error;

/** 对外可区分的错误码。 */
public enum ErrorCode {
    UNSUPPORTED_VERSION,
    MISSING_REQUIRED_FIELD,
    INCOMPATIBLE_VERSION,
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    UPSTREAM_TIMEOUT,
    UPSTREAM_ERROR,
    UPSTREAM_MALFORMED_RESPONSE,
    CIRCUIT_OPEN
}
