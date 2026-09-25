package com.github.lisama569.versionedapigatewayjava.upstream;

/** 上游处理成功后的凭证。 */
public record UpstreamReceipt(String upstreamOrderId, String status) {
}
