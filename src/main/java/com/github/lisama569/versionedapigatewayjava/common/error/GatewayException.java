package com.github.lisama569.versionedapigatewayjava.common.error;

/**
 * 网关内部唯一允许向 Web 层传播的异常类型。
 * rule  = 命中的规则，basis = 判定依据，均用于可解释响应与日志。
 */
public class GatewayException extends RuntimeException {

    private final GatewayErrorCode errorCode;
    private final String rule;
    private final String basis;
    private final String requestId;

    public GatewayException(GatewayErrorCode errorCode, String rule, String basis) {
        this(errorCode, rule, basis, null);
    }

    public GatewayException(GatewayErrorCode errorCode, String rule, String basis, String requestId) {
        super(rule + ": " + basis);
        this.errorCode = errorCode;
        this.rule = rule;
        this.basis = basis;
        this.requestId = requestId;
    }

    public GatewayErrorCode getErrorCode() {
        return errorCode;
    }

    public String getRule() {
        return rule;
    }

    public String getBasis() {
        return basis;
    }

    public String getRequestId() {
        return requestId;
    }
}
