package com.github.lisama569.versionedapigatewayjava.gateway;

import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamReceipt;

/**
 * 网关成功响应。
 * degraded=true 表示这是熔断期间的降级结果（未真正调用上游、未产生副作用），
 * 与真实上游成功（degraded=false 且 receipt 为真实凭证）可明确区分。
 */
public record GatewayResponse(String requestId, String version, String internalOrderSummary,
                              boolean replayed, boolean degraded, UpstreamReceipt receipt) {

    /** 兼容旧构造：非降级。 */
    public GatewayResponse(String requestId, String version, String internalOrderSummary,
                           boolean replayed, UpstreamReceipt receipt) {
        this(requestId, version, internalOrderSummary, replayed, false, receipt);
    }
}
