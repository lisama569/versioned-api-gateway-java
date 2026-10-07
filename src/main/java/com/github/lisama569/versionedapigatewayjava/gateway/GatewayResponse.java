package com.github.lisama569.versionedapigatewayjava.gateway;

import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamReceipt;

/**
 * 网关成功响应。
 *
 * @param degraded 为 true 表示这是熔断期间的降级结果：可读、可分辨，
 *                 但不是真实上游成功，也不会被记入幂等记录。
 */
public record GatewayResponse(String requestId, String version, String internalOrderSummary,
                              boolean replayed, UpstreamReceipt receipt, boolean degraded) {

    /** 兼容旧签名：非降级响应。 */
    public GatewayResponse(String requestId, String version, String internalOrderSummary,
                           boolean replayed, UpstreamReceipt receipt) {
        this(requestId, version, internalOrderSummary, replayed, receipt, false);
    }

    /** 构造降级响应：无真实上游凭证，receipt 为 null，degraded=true。 */
    public static GatewayResponse degraded(String requestId, String version, String summary) {
        return new GatewayResponse(requestId, version, summary, false, null, true);
    }
}
