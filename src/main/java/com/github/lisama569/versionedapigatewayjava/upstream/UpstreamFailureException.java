package com.github.lisama569.versionedapigatewayjava.upstream;

/** 上游返回错误状态（内部异常，绝不直接暴露给调用方）。 */
public class UpstreamFailureException extends RuntimeException {
    private final int status;

    public UpstreamFailureException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
