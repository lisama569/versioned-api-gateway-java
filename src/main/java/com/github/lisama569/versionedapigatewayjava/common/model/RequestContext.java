package com.github.lisama569.versionedapigatewayjava.common.model;

import com.github.lisama569.versionedapigatewayjava.version.ApiVersion;

/**
 * 单次请求的上下文：请求标识、调用方、API 版本。
 */
public record RequestContext(String requestId, String callerId, ApiVersion version) {
}
