package com.github.lisama569.versionedapigatewayjava.model;

/** 统一内部模型：所有 API 版本的请求都映射到该模型。 */
public record InternalOrder(String customerId, String itemName, int quantity, String note) {
}
