package com.github.lisama569.versionedapigatewayjava.upstream;

/** 上游调用异常（超时/错误状态），绝不允许直接传播到 Web 层。 */
public class UpstreamException extends RuntimeException {

    private final UpstreamFailureKind kind;
    private final Integer upstreamStatus;

    public UpstreamException(UpstreamFailureKind kind, Integer upstreamStatus, String message) {
        super(message);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
    }

    public UpstreamFailureKind getKind() {
        return kind;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }
}
