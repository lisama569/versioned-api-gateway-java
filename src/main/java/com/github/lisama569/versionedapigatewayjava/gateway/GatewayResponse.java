package com.github.lisama569.versionedapigatewayjava.gateway;

import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamReceipt;

/** 网关成功响应。 */
public record GatewayResponse(String requestId, String version, String internalOrderSummary,
                              boolean replayed, UpstreamReceipt receipt) {
}
