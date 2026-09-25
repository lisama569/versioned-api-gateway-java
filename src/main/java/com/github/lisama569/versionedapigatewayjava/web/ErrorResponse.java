package com.github.lisama569.versionedapigatewayjava.web;

/** 统一可解释错误响应。 */
public record ErrorResponse(String requestId, String code, String rule, String basis, String message) {
}
