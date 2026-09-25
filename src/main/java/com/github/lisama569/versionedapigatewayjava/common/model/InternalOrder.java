package com.github.lisama569.versionedapigatewayjava.common.model;

import java.math.BigDecimal;

/**
 * 统一内部订单模型：所有 API 版本的字段（新增、重命名、废弃）最终都映射到这里。
 *
 * <ul>
 *   <li>productName <- v1/v2/v3 的 product_name 或 v2 兼容别名 name</li>
 *   <li>quantity    <- 各版本通用，必填</li>
 *   <li>unitPrice   <- v1/v2 单位分(整数) unit_price_cents；v3 重命名为元(小数) unitPrice</li>
 *   <li>currency    <- v2 起新增；v1 缺失时按默认规则补 USD</li>
 *   <li>remark      <- v3 起新增，可空</li>
 * </ul>
 */
public final class InternalOrder {

    private final String idempotencyKey;
    private final String productName;
    private final int quantity;
    private final long unitPriceCents;
    private final String currency;
    private final String remark;

    public InternalOrder(String idempotencyKey,
                         String productName,
                         int quantity,
                         long unitPriceCents,
                         String currency,
                         String remark) {
        this.idempotencyKey = idempotencyKey;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
        this.currency = currency;
        this.remark = remark;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getRemark() {
        return remark;
    }

    /** 总金额（分），便于上游/回包统一计算。 */
    public long totalAmountCents() {
        return unitPriceCents * quantity;
    }

    public BigDecimal totalAmountYuan() {
        return BigDecimal.valueOf(totalAmountCents()).movePointLeft(2);
    }
}
