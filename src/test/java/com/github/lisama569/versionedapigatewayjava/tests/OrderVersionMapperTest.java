package com.github.lisama569.versionedapigatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayErrorCode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayException;
import com.github.lisama569.versionedapigatewayjava.common.model.InternalOrder;
import com.github.lisama569.versionedapigatewayjava.version.ApiVersion;
import com.github.lisama569.versionedapigatewayjava.version.OrderVersionMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 跨版本字段兼容：同一资源在 v1/v2/v3 的新增、重命名、废弃规则，
 * 必须稳定映射到统一内部模型；不兼容请求必须按可区分原因拒绝。
 */
class OrderVersionMapperTest {

    private static final Logger log = LoggerFactory.getLogger(OrderVersionMapperTest.class);
    private final ObjectMapper om = new ObjectMapper();
    private final OrderVersionMapper mapper = new OrderVersionMapper();

    private JsonNode json(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void logMapping(String caseName, InternalOrder o) {
        log.info("test=VersionMapper case={} rule=MAPPED basis=product={} qty={} cents={} currency={} remark={}",
                caseName, o.getProductName(), o.getQuantity(), o.getUnitPriceCents(),
                o.getCurrency(), o.getRemark());
    }

    @Test
    void v1MapsAndDefaultsCurrencyToUsd() {
        InternalOrder o = mapper.map(ApiVersion.V1,
                json("{\"product_name\":\"widget\",\"quantity\":2,\"unit_price_cents\":150}"), "k1");
        logMapping("v1-default", o);
        assertEquals("widget", o.getProductName());
        assertEquals(300, o.totalAmountCents());
        assertEquals("USD", o.getCurrency(), "v1 无币种字段，按规则补 USD");
    }

    @Test
    void v2AcceptsLegacyAliasNameAndOptionalCurrency() {
        InternalOrder o = mapper.map(ApiVersion.V2,
                json("{\"name\":\"gadget\",\"quantity\":1,\"unit_price_cents\":999}"), "k2");
        logMapping("v2-alias", o);
        assertEquals("gadget", o.getProductName(), "重命名前身 name 在 v2 仍兼容映射到 product_name");
        assertEquals("USD", o.getCurrency());

        InternalOrder o2 = mapper.map(ApiVersion.V2,
                json("{\"product_name\":\"g2\",\"quantity\":1,\"unit_price_cents\":10,\"currency\":\"EUR\"}"), "k3");
        assertEquals("EUR", o2.getCurrency(), "v2 新增 currency 字段生效");
    }

    @Test
    void v3RenamesPriceToYuanAndRequiresCurrency() {
        InternalOrder o = mapper.map(ApiVersion.V3,
                json("{\"product_name\":\"thing\",\"quantity\":3,\"unitPrice\":1.5,\"currency\":\"CNY\",\"remark\":\"r\"}"),
                "k4");
        logMapping("v3-yuan", o);
        assertEquals(450, o.totalAmountCents(), "1.5 元 * 3 = 450 分，重命名+单位换算规则");
        assertEquals("CNY", o.getCurrency());
        assertEquals("r", o.getRemark());
    }

    @Test
    void v3RejectsRemovedAndRenamedLegacyFields() {
        GatewayException e1 = assertThrows(GatewayException.class, () ->
                mapper.map(ApiVersion.V3,
                        json("{\"name\":\"x\",\"quantity\":1,\"unitPrice\":1,\"currency\":\"USD\"}"), null));
        assertEquals(GatewayErrorCode.DEPRECATED_FIELD, e1.getErrorCode());
        assertEquals("FIELD_REMOVED_NAME", e1.getRule());
        log.info("test=VersionMapper case=v3-name-removed rule={} basis={}", e1.getRule(), e1.getBasis());

        GatewayException e2 = assertThrows(GatewayException.class, () ->
                mapper.map(ApiVersion.V3,
                        json("{\"product_name\":\"x\",\"quantity\":1,\"unit_price_cents\":100,\"currency\":\"USD\"}"),
                        null));
        assertEquals(GatewayErrorCode.DEPRECATED_FIELD, e2.getErrorCode());
        assertEquals("FIELD_RENAMED_UNIT_PRICE_CENTS", e2.getRule());
        log.info("test=VersionMapper case=v3-cents-renamed rule={} basis={}", e2.getRule(), e2.getBasis());
    }

    @Test
    void missingRequiredFieldsAreRejectedDistinguishablyAcrossVersions() {
        // v1 缺 product_name
        GatewayException e1 = assertThrows(GatewayException.class, () ->
                mapper.map(ApiVersion.V1, json("{\"quantity\":1,\"unit_price_cents\":1}"), null));
        assertEquals(GatewayErrorCode.MISSING_REQUIRED_FIELD, e1.getErrorCode());
        log.info("test=VersionMapper case=v1-missing-product rule={} basis={}", e1.getRule(), e1.getBasis());

        // v2 新旧名都缺
        GatewayException e2 = assertThrows(GatewayException.class, () ->
                mapper.map(ApiVersion.V2, json("{\"quantity\":1,\"unit_price_cents\":1}"), null));
        assertEquals(GatewayErrorCode.MISSING_REQUIRED_FIELD, e2.getErrorCode());

        // v3 缺 currency（v1/v2 可缺，v3 必填）
        GatewayException e3 = assertThrows(GatewayException.class, () ->
                mapper.map(ApiVersion.V3, json("{\"product_name\":\"x\",\"quantity\":1,\"unitPrice\":1}"), null));
        assertEquals(GatewayErrorCode.MISSING_REQUIRED_FIELD, e3.getErrorCode());
        log.info("test=VersionMapper case=v3-missing-currency rule={} basis={}", e3.getRule(), e3.getBasis());
    }

    @Test
    void invalidValuesAreRejectedAndDifferFromMissingFields() {
        GatewayException badQty = assertThrows(GatewayException.class, () ->
                mapper.map(ApiVersion.V1,
                        json("{\"product_name\":\"x\",\"quantity\":0,\"unit_price_cents\":1}"), null));
        assertEquals(GatewayErrorCode.INVALID_FIELD_VALUE, badQty.getErrorCode());
        assertEquals("FIELD_RANGE_QUANTITY", badQty.getRule());

        GatewayException badScale = assertThrows(GatewayException.class, () ->
                mapper.map(ApiVersion.V3,
                        json("{\"product_name\":\"x\",\"quantity\":1,\"unitPrice\":1.234,\"currency\":\"USD\"}"),
                        null));
        assertEquals(GatewayErrorCode.INVALID_FIELD_VALUE, badScale.getErrorCode());
        assertEquals("FIELD_SCALE_PRICE", badScale.getRule());
        log.info("test=VersionMapper case=v3-price-scale rule={} basis={}", badScale.getRule(), badScale.getBasis());
    }
}
