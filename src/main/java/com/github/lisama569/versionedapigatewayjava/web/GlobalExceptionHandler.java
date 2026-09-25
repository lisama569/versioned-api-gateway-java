package com.github.lisama569.versionedapigatewayjava.web;

import com.github.lisama569.versionedapigatewayjava.common.error.GatewayErrorCode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把一切异常归一化为 {@link ErrorResponse}。
 * 对外只暴露 错误码 + 规则 + 判定依据，原始上游异常/堆栈绝不进入响应体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex, HttpServletRequest request) {
        String requestId = resolveRequestId(ex, request);
        ErrorResponse body = new ErrorResponse(
                requestId,
                ex.getErrorCode().name(),
                ex.getRule(),
                ex.getBasis(),
                ex.getErrorCode().name());
        log.warn("requestId={} http-error code={} rule={} basis={}",
                requestId, ex.getErrorCode().name(), ex.getRule(), ex.getBasis());
        return ResponseEntity.status(ex.getErrorCode().httpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex, HttpServletRequest request) {
        // 兜底：只记录内部细节，对外返回不含内部信息的 INTERNAL_ERROR
        String requestId = headerOrGenerated(request);
        log.error("requestId={} http-error code=INTERNAL_ERROR unhandled=", requestId, ex);
        ErrorResponse body = new ErrorResponse(
                requestId,
                GatewayErrorCode.INTERNAL_ERROR.name(),
                "INTERNAL_ERROR",
                "发生未预期的内部错误，请凭请求标识联系管理员",
                GatewayErrorCode.INTERNAL_ERROR.name());
        return ResponseEntity.internalServerError().body(body);
    }

    private String resolveRequestId(GatewayException ex, HttpServletRequest request) {
        if (ex.getRequestId() != null && !ex.getRequestId().isBlank()) {
            return ex.getRequestId();
        }
        return headerOrGenerated(request);
    }

    private String headerOrGenerated(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader("X-Request-Id");
        return (header == null || header.isBlank()) ? "n/a" : header.trim();
    }
}
