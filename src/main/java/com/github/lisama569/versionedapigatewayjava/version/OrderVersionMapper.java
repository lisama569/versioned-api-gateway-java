package com.github.lisama569.versionedapigatewayjava.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayErrorCode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayException;
import com.github.lisama569.versionedapigatewayjava.common.model.InternalOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 按版本把外部请求体映射为统一内部模型 {@link InternalOrder}。
 *
 * <p>稳定的版本兼容规则：</p>
 * <ul>
 *   <li>V1：product_name/quantity/unit_price_cents（分，整数）必填；无 currency 字段，
 *       按 {@code CURRENCY_DEFAULT_V1} 规则补 {@code USD}。</li>
 *   <li>V2：新增可空 currency（缺省仍按 {@code CURRENCY_DEFAULT_LEGACY} 补 USD）；
 *       旧字段 name 作为 product_name 的重命名前身仍被兼容（{@code FIELD_RENAME_NAME}），
 *       但标记为已弃用并记录日志。</li>
 *   <li>V3：name 正式移除（出现即按 {@code DEPRECATED_FIELD} 拒绝）；
 *       unit_price_cents 重命名为 unitPrice（元，最多两位小数），旧字段出现即拒绝；
 *       currency 升级为必填；新增可空 remark。</li>
 * </ul>
 * 任何拒绝都携带规则名与判定依据（字段路径 + 实际情况），便于调用方区分原因。
 */
@Component
public class OrderVersionMapper {

    private static final Logger log = LoggerFactory.getLogger(OrderVersionMapper.class);

    private static final String DEFAULT_CURRENCY = "USD";
    private static final int MAX_QUANTITY = 10_000;

    public InternalOrder map(ApiVersion version, JsonNode body, String idempotencyKey) {
        if (body == null || !body.isObject()) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "REQUEST_BODY_NOT_OBJECT",
                    "version=" + version.displayName() + ", body 必须是 JSON 对象");
        }
        InternalOrder order = switch (version) {
            case V1 -> mapV1(body);
            case V2 -> mapV2(body);
            case V3 -> mapV3(body);
        };
        return new InternalOrder(idempotencyKey,
                order.getProductName(),
                order.getQuantity(),
                order.getUnitPriceCents(),
                order.getCurrency(),
                order.getRemark());
    }

    /* ---------------- V1 ---------------- */

    private InternalOrder mapV1(JsonNode body) {
        String productName = requireText(body, "product_name", ApiVersion.V1);
        int quantity = requireQuantity(body, ApiVersion.V1);
        long cents = requirePriceCents(body, "unit_price_cents", ApiVersion.V1);
        // 规则：V1 没有币种概念，统一补 USD（旧版本语义不变，内部模型完整）
        log.debug("rule=CURRENCY_DEFAULT_V1 field=currency basis=field_absent_in_v1 default={}", DEFAULT_CURRENCY);
        return new InternalOrder(null, productName, quantity, cents, DEFAULT_CURRENCY, null);
    }

    /* ---------------- V2 ---------------- */

    private InternalOrder mapV2(JsonNode body) {
        String productName;
        if (hasNonBlank(body, "product_name")) {
            productName = text(body, "product_name");
            if (body.hasNonNull("name")) {
                // 两个名字同时出现：以新名为准，旧名忽略，规则可解释
                log.debug("rule=FIELD_RENAME_NAME basis=both_present prefer=product_name ignored=name");
            }
        } else if (hasNonBlank(body, "name")) {
            // 规则：name -> product_name 的重命名兼容期
            productName = text(body, "name");
            log.debug("rule=FIELD_RENAME_NAME basis=legacy_alias_accepted field=name mapped_to=product_name");
        } else {
            throw missingField("product_name", ApiVersion.V2,
                    "旧别名 name 也可接受（已弃用），但二者至少提供一个");
        }

        int quantity = requireQuantity(body, ApiVersion.V2);
        long cents = requirePriceCents(body, "unit_price_cents", ApiVersion.V2);

        String currency;
        if (hasNonBlank(body, "currency")) {
            currency = text(body, "currency");
        } else {
            currency = DEFAULT_CURRENCY;
            log.debug("rule=CURRENCY_DEFAULT_LEGACY field=currency basis=optional_absent default={}", DEFAULT_CURRENCY);
        }
        return new InternalOrder(null, productName, quantity, cents, currency, null);
    }

    /* ---------------- V3 ---------------- */

    private InternalOrder mapV3(JsonNode body) {
        // 规则：name 在 V3 已移除，出现即拒绝（而不是静默映射），避免旧客户端误以为仍被支持
        if (body.hasNonNull("name")) {
            throw reject(GatewayErrorCode.DEPRECATED_FIELD,
                    "FIELD_REMOVED_NAME",
                    "version=v3, 字段 name 已移除，请使用 product_name");
        }
        // 规则：分计价格段已重命名为人民币元计价 unitPrice，旧字段出现即拒绝
        if (body.hasNonNull("unit_price_cents")) {
            throw reject(GatewayErrorCode.DEPRECATED_FIELD,
                    "FIELD_RENAMED_UNIT_PRICE_CENTS",
                    "version=v3, unit_price_cents 已重命名为 unitPrice（单位：元）");
        }

        String productName = requireText(body, "product_name", ApiVersion.V3);
        int quantity = requireQuantity(body, ApiVersion.V3);
        long cents = requirePriceYuan(body, "unitPrice", ApiVersion.V3);

        // 规则：currency 在 V3 升级为必填，缺失必须拒绝而不是猜测默认值
        String currency;
        if (hasNonBlank(body, "currency")) {
            currency = text(body, "currency");
        } else {
            throw missingField("currency", ApiVersion.V3,
                    "自 v3 起 currency 为必填字段（v1/v2 缺省为 USD）");
        }

        String remark = body.hasNonNull("remark") ? text(body, "remark") : null;
        return new InternalOrder(null, productName, quantity, cents, currency, remark);
    }

    /* ---------------- 共享字段校验 ---------------- */

    private String requireText(JsonNode body, String field, ApiVersion version) {
        if (!hasNonBlank(body, field)) {
            throw missingField(field, version, "字段缺失或为空字符串");
        }
        return text(body, field);
    }

    private int requireQuantity(JsonNode body, ApiVersion version) {
        String field = "quantity";
        if (!body.hasNonNull(field)) {
            throw missingField(field, version, "字段缺失");
        }
        JsonNode node = body.get(field);
        if (!node.isIntegralNumber() || node.isBoolean()) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_TYPE_QUANTITY",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 期望整数，实际=" + node.getNodeType());
        }
        int q = node.intValue();
        if (q < 1 || q > MAX_QUANTITY) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_RANGE_QUANTITY",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 允许区间 [1," + MAX_QUANTITY + "]，实际=" + q);
        }
        return q;
    }

    private long requirePriceCents(JsonNode body, String field, ApiVersion version) {
        if (!body.hasNonNull(field)) {
            throw missingField(field, version, "字段缺失");
        }
        JsonNode node = body.get(field);
        if (!node.isIntegralNumber() || node.isBoolean()) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_TYPE_PRICE",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 期望整数（分），实际=" + node.getNodeType());
        }
        long cents = node.longValue();
        if (cents < 0) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_RANGE_PRICE",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 价格不能为负，实际=" + cents);
        }
        return cents;
    }

    private long requirePriceYuan(JsonNode body, String field, ApiVersion version) {
        if (!body.hasNonNull(field)) {
            throw missingField(field, version, "字段缺失（自 v3 起以元计价）");
        }
        JsonNode node = body.get(field);
        if (!node.isNumber() || node.isBoolean()) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_TYPE_PRICE",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 期望数字（元），实际=" + node.getNodeType());
        }
        BigDecimal yuan;
        try {
            yuan = new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_TYPE_PRICE",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 无法解析为数字，原始=" + node.asText());
        }
        if (yuan.signum() < 0) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_RANGE_PRICE",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 价格不能为负，实际=" + yuan);
        }
        if (yuan.stripTrailingZeros().scale() > 2) {
            throw reject(GatewayErrorCode.INVALID_FIELD_VALUE,
                    "FIELD_SCALE_PRICE",
                    "version=" + version.displayName() + ", field=" + field
                            + ", 元价最多两位小数，实际=" + yuan.toPlainString());
        }
        return yuan.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    /* ---------------- 小工具 ---------------- */

    private boolean hasNonBlank(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private String text(JsonNode body, String field) {
        return body.get(field).asText().trim();
    }

    private GatewayException missingField(String field, ApiVersion version, String detail) {
        return reject(GatewayErrorCode.MISSING_REQUIRED_FIELD,
                "MISSING_REQUIRED_FIELD",
                "version=" + version.displayName() + ", field=" + field + ", " + detail);
    }

    private GatewayException reject(GatewayErrorCode code, String rule, String basis) {
        log.debug("rule={} basis={}", rule, basis);
        return new GatewayException(code, rule, basis);
    }
}
