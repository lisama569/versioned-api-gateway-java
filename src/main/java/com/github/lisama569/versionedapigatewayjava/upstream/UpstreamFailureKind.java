package com.github.lisama569.versionedapigatewayjava.upstream;

/** 上游故障的内部分类，仅供归一化使用，不直接对外暴露。 */
public enum UpstreamFailureKind {
    TIMEOUT,
    ERROR_STATUS
}
