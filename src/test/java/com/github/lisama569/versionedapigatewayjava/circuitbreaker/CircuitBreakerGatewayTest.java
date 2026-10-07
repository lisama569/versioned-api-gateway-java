package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
import com.github.lisama569.versionedapigatewayjava.gateway.GatewayResponse;
import com.github.lisama569.versionedapigatewayjava.gateway.GatewayService;
import com.github.lisama569.versionedapigatewayjava.idempotency.IdempotencyStore;
import com.github.lisama569.versionedapigatewayjava.ratelimit.CallerPolicy;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimiter;
import com.github.lisama569.versionedapigatewayjava.upstream.SimulatedUpstreamClient;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamKind;
import com.github.lisama569.versionedapigatewayjava.versioning.VersionRouter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关级熔断与降级集成测试（本地模拟上游 + 假时钟，无网络无凭据）：
 * 熔断打开快速失败、降级结果可分辨且不污染幂等记录、冷却试探恢复、恢复后再次熔断、
 * 以及不携带可选请求头时正常请求照常工作。
 */
class CircuitBreakerGatewayTest {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerGatewayTest.class);

    private AtomicLong clock;
    private SimulatedUpstreamClient upstream;
    private GatewayService gateway;
    private CircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0);
        upstream = new SimulatedUpstreamClient();
        RateLimiter limiter = new RateLimiter(new CallerPolicy(100, 100.0d, 10_000, 60_000), clock::get);
        // 阈值 2，窗口 10s，冷却 5s，半开放行 1，状态 TTL 60s
        registry = new CircuitBreakerRegistry(
                new CircuitBreakerConfig(2, 10_000, 5_000, 1, 60_000), clock::get);
        gateway = new GatewayService(new VersionRouter(), limiter, upstream,
                new IdempotencyStore(), registry, GatewayService.DEFAULT_DEPENDENCY, 100, 2);
    }

    private String rid() {
        return UUID.randomUUID().toString();
    }

    private String v2Body() {
        return "{\"customerId\":\"cust-1\",\"itemName\":\"book\",\"quantity\":1}";
    }

    private void failOnce(String key) {
        upstream.enqueue(UpstreamKind.ERROR_STATUS);
        assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", key, v2Body()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));
    }

    @Test
    void openCircuitFailsFastWithoutHittingUpstream() {
        String requestId = rid();
        failOnce("k-f1");
        failOnce("k-f2"); // 达到阈值 2 -> OPEN
        int invocationsBefore = upstream.invocationCount();

        assertThatThrownBy(() -> gateway.process(requestId, "v2", "caller-1", "k-f3", v2Body()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CIRCUIT_OPEN);
                    assertThat(ex.retryAfterMillis()).isNotNull().isPositive();
                });
        assertThat(upstream.invocationCount())
                .as("open circuit must not hit upstream").isEqualTo(invocationsBefore);
        log.info("[{}] matched rule=circuit-open-fail-fast basis='2 upstream errors in window -> OPEN; "
                + "next request rejected CIRCUIT_OPEN with retryAfter, upstream invocations unchanged={}'",
                requestId, invocationsBefore);
    }

    @Test
    void degradedResponseIsDistinguishableAndNotCommittedToIdempotency() {
        String requestId = rid();
        failOnce("k-d1");
        failOnce("k-d2"); // OPEN

        // 可降级请求：拿到可读、可分辨的降级结果，而不是错误
        GatewayResponse degraded =
                gateway.process(requestId, "v2", "caller-1", "degrade-key", v2Body(), true);
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.receipt()).isNull();
        assertThat(degraded.replayed()).isFalse();
        assertThat(degraded.internalOrderSummary()).isEqualTo("cust-1/book x1");
        log.info("[{}] matched rule=degraded-distinguishable basis='degraded=true, receipt=null, "
                + "summary readable; caller can tell fallback from real success'", requestId);

        // 冷却 -> 试探恢复（上游回到 OK）
        clock.addAndGet(5_000);
        upstream.enqueue(UpstreamKind.OK);
        GatewayResponse probe = gateway.process(rid(), "v2", "caller-1", "probe-key", v2Body());
        assertThat(probe.degraded()).isFalse();

        // 关键：降级期间同键未写入幂等记录，恢复后真实请求仍生效且只生效一次
        GatewayResponse real =
                gateway.process(requestId, "v2", "caller-1", "degrade-key", v2Body(), true);
        assertThat(real.degraded()).isFalse();
        assertThat(real.replayed()).isFalse();
        assertThat(real.receipt()).isNotNull();
        assertThat(real.receipt().status()).isEqualTo("ACCEPTED");
        assertThat(upstream.effectCount())
                .as("real request after degradation must apply side effect exactly once").isEqualTo(2);
        log.info("[{}] matched rule=degraded-not-committed basis='degraded result skipped idempotency store; "
                + "same key after recovery executed for real, replayed=false, effects count it once'",
                requestId);
    }

    @Test
    void cooldownProbeRecoversThenFreshFailuresReopen() {
        String requestId = rid();
        failOnce("k-r1");
        failOnce("k-r2"); // OPEN

        clock.addAndGet(5_000); // 冷却结束 -> HALF_OPEN，放行 1 个试探
        upstream.enqueue(UpstreamKind.OK);
        GatewayResponse probe = gateway.process(requestId, "v2", "caller-1", "probe-1", v2Body());
        assertThat(probe.degraded()).isFalse();
        assertThat(registry.breakerFor(GatewayService.DEFAULT_DEPENDENCY).state())
                .isEqualTo(CircuitState.CLOSED);
        log.info("[{}] matched rule=probe-recover basis='cooldown 5000ms elapsed, single probe OK -> CLOSED'",
                requestId);

        // 恢复后再次抖动 -> 再次熔断
        failOnce("k-r3");
        failOnce("k-r4");
        assertThat(registry.breakerFor(GatewayService.DEFAULT_DEPENDENCY).state())
                .isEqualTo(CircuitState.OPEN);
        assertThatThrownBy(() -> gateway.process(requestId, "v2", "caller-1", "k-r5", v2Body()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CIRCUIT_OPEN));
        log.info("[{}] matched rule=rebreak basis='2 fresh failures after recovery -> OPEN again, "
                + "fail-fast resumes'", requestId);
    }

    @Test
    void timeoutAndMalformedAlsoCountTowardCircuitFailures() {
        String requestId = rid();
        upstream.enqueue(UpstreamKind.TIMEOUT);
        upstream.enqueue(UpstreamKind.TIMEOUT);
        assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", "k-t1", v2Body()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));

        upstream.enqueue(UpstreamKind.MALFORMED);
        assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", "k-m1", v2Body()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_MALFORMED_RESPONSE));

        // 超时与畸形同样计入窗口，累计 2 次 -> OPEN
        assertThat(registry.breakerFor(GatewayService.DEFAULT_DEPENDENCY).state())
                .isEqualTo(CircuitState.OPEN);
        log.info("[{}] matched rule=failure-kinds-counted basis='UPSTREAM_TIMEOUT + "
                + "UPSTREAM_MALFORMED_RESPONSE both counted, threshold 2 -> OPEN'", requestId);
    }

    @Test
    void requestWithoutOptionalHeadersStillWorks() {
        String requestId = rid();
        // 不携带调用方标识与幂等键：限流走 anonymous 维度，幂等跳过去重，请求照常成功
        GatewayResponse response = gateway.process(requestId, "v2", null, null, v2Body());
        assertThat(response.degraded()).isFalse();
        assertThat(response.replayed()).isFalse();
        assertThat(response.receipt().status()).isEqualTo("ACCEPTED");
        assertThat(upstream.effectCount()).isEqualTo(1);
        log.info("[{}] matched rule=optional-headers-absent basis='null callerId -> anonymous bucket, "
                + "null idempotencyKey -> no dedup, request succeeded normally'", requestId);
    }
}
