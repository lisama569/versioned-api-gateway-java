package com.github.lisama569.versionedapigatewayjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayErrorCode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayException;
import com.github.lisama569.versionedapigatewayjava.common.model.OrderResult;
import com.github.lisama569.versionedapigatewayjava.idempotency.IdempotencyService;
import com.github.lisama569.versionedapigatewayjava.ratelimit.MutableClock;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimitConfig;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimitService;
import com.github.lisama569.versionedapigatewayjava.service.OrderGatewayService;
import com.github.lisama569.versionedapigatewayjava.upstream.MockUpstreamClient;
import com.github.lisama569.versionedapigatewayjava.version.OrderVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 上游故障归一化与降级测试：
 * 超时 -> UPSTREAM_TIMEOUT(504)；错误状态 -> UPSTREAM_ERROR(502)；畸形 -> UPSTREAM_MALFORMED(502)；
 * 503 幂等安全重试一次；重复提交不产生第二次副作用。全程本地模拟。
 */
class UpstreamNormalizationTest {

    private static final Logger log = LoggerFactory.getLogger(UpstreamNormalizationTest.class);

    private MockUpstreamClient upstream;
    private OrderGatewayService gateway;

    @BeforeEach
    void setUp() {
        upstream = new MockUpstreamClient();
        MutableClock clock = new MutableClock(0);
        RateLimitConfig cfg = new RateLimitConfig(10, 1000, 1000.0, 1000, 60_000);
        RateLimitService rl = new RateLimitService(clock, cfg);
        IdempotencyService idem = new IdempotencyService();
        gateway = new OrderGatewayService(new OrderVersionMapper(), rl, idem, upstream, new ObjectMapper());
    }

    private String v1Body(String product) {
        return "{\"product_name\":\"" + product + "\",\"quantity\":2,\"unit_price_cents\":150}";
    }

    private GatewayException callAndExpectError(String caseName, String product, String key) {
        try {
            gateway.placeOrder("caller-x", "v1", "req-" + caseName, key, v1Body(product));
            throw new AssertionError(caseName + " 期望被拒绝");
        } catch (GatewayException e) {
            log.info("test=Upstream case={} rule={} code={} basis={}",
                    caseName, e.getRule(), e.getErrorCode(), e.getBasis());
            // 归一化保证：对外信息中不得包含原始上游异常类名/原始消息
            assertFalse(e.getBasis().contains("UpstreamException"), "原始上游异常类型不得外泄");
            return e;
        }
    }

    @Test
    void timeoutMapsToUpstreamTimeoutWithoutRetry() {
        GatewayException e = callAndExpectError("timeout", "TIMEOUT:widget", null);
        assertEquals(GatewayErrorCode.UPSTREAM_TIMEOUT, e.getErrorCode());
        assertEquals(504, e.getErrorCode().httpStatus());
        assertEquals("UPSTREAM_TIMEOUT", e.getRule());
        assertEquals(1, upstream.invocationCount(), "超时不重试，只调用一次");
    }

    @Test
    void errorStatusMapsToUpstreamError() {
        GatewayException e = callAndExpectError("status500", "STATUS_500:widget", null);
        assertEquals(GatewayErrorCode.UPSTREAM_ERROR, e.getErrorCode());
        assertEquals(502, e.getErrorCode().httpStatus());
        assertEquals("UPSTREAM_ERROR_STATUS", e.getRule());
        assertEquals(1, upstream.invocationCount(), "500 不重试");
    }

    @Test
    void malformedBodyMapsToUpstreamMalformed() {
        GatewayException e = callAndExpectError("malformed", "MALFORMED:widget", null);
        assertEquals(GatewayErrorCode.UPSTREAM_MALFORMED, e.getErrorCode());
        assertEquals(502, e.getErrorCode().httpStatus());
        assertEquals("UPSTREAM_MALFORMED_BODY", e.getRule());
        assertEquals(1, upstream.invocationCount());
    }

    @Test
    void retryOnceOn503ThenSucceedsAndDuplicateRequestHasNoExtraSideEffect() {
        String key = "idem-503";
        OrderResult first = gateway.placeOrder("caller-x", "v1", "req-retry", key,
                v1Body("OK_503:widget"));
        assertEquals("ACCEPTED", first.status());
        assertEquals(new BigDecimal("3.00"), first.totalAmount());
        assertEquals(2, upstream.invocationCount(key),
                "首次 503 后允许在幂等保护下安全重试一次（共两次上游调用）");

        // 客户端重复提交：返回相同归一化结果，但上游副作用不再发生
        OrderResult second = gateway.placeOrder("caller-x", "v1", "req-dup", key,
                v1Body("OK_503:widget"));
        assertEquals(first.orderId(), second.orderId());
        assertEquals(2, upstream.invocationCount(key),
                "重复请求必须命中幂等缓存，上游调用次数不得增加");
        log.info("test=Upstream case=duplicate rule=IDEMPOTENT_DEDUP basis=invocationsForKey={}",
                upstream.invocationCount(key));
    }

    @Test
    void successIsNormalizedAcrossVersions() {
        OrderResult r = gateway.placeOrder("caller-x", "v3", "req-ok", "key-ok",
                "{\"product_name\":\"thing\",\"quantity\":2,\"unitPrice\":1.25,\"currency\":\"CNY\"}");
        assertEquals("ACCEPTED", r.status());
        assertEquals("CNY", r.currency());
        assertEquals(new BigDecimal("2.50"), r.totalAmount(), "v3 元价映射后金额一致");
        log.info("test=Upstream case=success rule=NORMALIZED basis=orderId={} amount={} currency={}",
                r.orderId(), r.totalAmount(), r.currency());
    }
}
