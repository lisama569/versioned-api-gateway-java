package com.github.lisama569.versionedapigatewayjava.upstream;

/** 上游原始响应：状态码 + 未解析的响应体（可能畸形）。 */
public record UpstreamResponse(int status, String rawBody) {
}
