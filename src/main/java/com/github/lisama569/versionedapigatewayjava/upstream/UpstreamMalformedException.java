package com.github.lisama569.versionedapigatewayjava.upstream;

/** 上游返回了无法解析的畸形响应（内部异常，绝不直接暴露给调用方）。 */
public class UpstreamMalformedException extends RuntimeException {
    public UpstreamMalformedException(String message) {
        super(message);
    }
}
