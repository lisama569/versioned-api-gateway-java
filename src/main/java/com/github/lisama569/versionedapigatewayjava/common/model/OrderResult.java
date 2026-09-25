package com.github.lisama569.versionedapigatewayjava.common.model;

import java.math.BigDecimal;

/**
 * 归一化的对外返回结果。无论调用方使用 v1/v2/v3，成功时拿到的结构与语义一致，
 * 因此不同版本调用方在同一入口下获得可解释、可比较的结果。
 */
public record OrderResult(
        String orderId,
        String idempotencyKey,
        String productName,
        int quantity,
        BigDecimal totalAmount,
        String currency,
        String status
) {
}
