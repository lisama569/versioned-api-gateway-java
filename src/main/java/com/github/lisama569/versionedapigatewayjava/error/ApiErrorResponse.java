package com.github.lisama569.versionedapigatewayjava.error;

/** 对外统一错误响应体。 */
public record ApiErrorResponse(String requestId, String code, String message, Long retryAfterMillis) {
}
