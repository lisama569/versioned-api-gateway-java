package com.github.lisama569.versionedapigatewayjava.upstream;

/** 本地模拟上游的故障注入类型。 */
public enum UpstreamKind {
    OK,
    TIMEOUT,
    TIMEOUT_AFTER_SIDE_EFFECT,
    ERROR_STATUS,
    MALFORMED
}
