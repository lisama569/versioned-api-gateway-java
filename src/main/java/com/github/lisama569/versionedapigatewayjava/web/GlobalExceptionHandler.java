package com.github.lisama569.versionedapigatewayjava.web;

import com.github.lisama569.versionedapigatewayjava.error.ApiErrorResponse;
import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一对外错误形态；映射可区分错误码到 HTTP 状态，绝不透出上游原始异常。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handle(ApiException ex) {
        String requestId = UUID.randomUUID().toString();
        HttpStatus status = httpStatus(ex.code());
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterMillis() != null) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.retryAfterMillis() / 1000)));
            headers.add("X-Retry-After-Ms", String.valueOf(ex.retryAfterMillis()));
        }
        log.warn("[{}] rejected: code={} httpStatus={} reason='{}' basis='error classification'",
                requestId, ex.code(), status.value(), ex.getMessage());
        return new ResponseEntity<>(
                new ApiErrorResponse(requestId, ex.code().name(), ex.getMessage(), ex.retryAfterMillis()),
                headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnknown(Exception ex) {
        String requestId = UUID.randomUUID().toString();
        // 只记录内部细节，对外返回通用错误，不泄露原始异常类名/堆栈
        log.error("[{}] unhandled exception (hidden from client)", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(requestId, "INTERNAL_ERROR", "internal server error", null));
    }

    private HttpStatus httpStatus(ErrorCode code) {
        return switch (code) {
            case UNSUPPORTED_VERSION, MISSING_REQUIRED_FIELD, INCOMPATIBLE_VERSION -> HttpStatus.BAD_REQUEST;
            case RATE_LIMITED, QUOTA_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case UPSTREAM_ERROR, UPSTREAM_MALFORMED_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case CIRCUIT_OPEN -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
