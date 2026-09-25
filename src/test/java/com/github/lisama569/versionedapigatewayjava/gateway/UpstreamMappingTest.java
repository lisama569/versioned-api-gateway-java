package com.github.lisama569.versionedapigatewayjava.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
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
 * 上游异常归一化与降级测试（纯本地模拟器，无真实网络/凭据）：
 * 超时->UPSTREAM_TIMEOUT、错误状态->UPSTREAM_ERROR、畸形->UPSTREAM_MALFORMED_RESPONSE；
 * 超时重试携带同一幂等键，不产生重复副作用；同键重放不重复调用上游。
 */
class UpstreamMappingTest {
    private static final Logger log = LoggerFactory.getLogger(UpstreamMappingTest.class);

    private SimulatedUpstreamClient upstream;
    private IdempotencyStore idempotency;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        upstream = new SimulatedUpstreamClient();
        idempotency = new IdempotencyStore();
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(new CallerPolicy(10, 10.0d, 1000, 60_000), clock::get);
        gateway = new GatewayService(new VersionRouter(), limiter, upstream, idempotency, 100, 2);
    }

    private String rid() {
        return UUID.randomUUID().toString();
    }

    private String v2Body() {
        return "{\"customerId\":\"cust-1\",\"itemName\":\"book\",\"quantity\":1}";
    }

    @Test
    void timeoutOnBothAttemptsMapsToUpstreamTimeout() {
        String requestId = rid();
        upstream.enqueue(UpstreamKind.TIMEOUT);
        upstream.enqueue(UpstreamKind.TIMEOUT);
        assertThatThrownBy(() -> gateway.process(requestId, "v2", "caller-1", "key-timeout", v2Body()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT);
                    assertThat(ex.getMessage()).doesNotContain("Upstream", "interrupted");
                });
        assertThat(upstream.invocationCount()).isEqualTo(2);
        assertThat(upstream.effectCount()).isEqualTo(0);
        log.info("[{}] matched rule=upstream-timeout basis='2 hung attempts at 100ms each -> UPSTREAM_TIMEOUT, raw exception hidden'",
                requestId);
    }

    @Test
    void errorStatusMapsToUpstreamErrorWithoutLeakingRawException() {
        String requestId = rid();
        upstream.setErrorStatus(503);
        upstream.enqueue(UpstreamKind.ERROR_STATUS);
        assertThatThrownBy(() -> gateway.process(requestId, "v2", "caller-1", "key-status", v2Body()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
                    assertThat(ex.getMessage()).doesNotContain("503", "UpstreamFailureException");
                });
        assertThat(upstream.invocationCount()).isEqualTo(1);
        log.info("[{}] matched rule=upstream-error-status basis='upstream 503 mapped to UPSTREAM_ERROR, no retry on non-timeout'",
                requestId);
    }

    @Test
    void malformedPayloadMapsToMalformedCode() {
        String requestId = rid();
        upstream.enqueue(UpstreamKind.MALFORMED);
        assertThatThrownBy(() -> gateway.process(requestId, "v2", "caller-1", "key-malformed", v2Body()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_MALFORMED_RESPONSE));
        log.info("[{}] matched rule=upstream-malformed basis='unparsable body -> UPSTREAM_MALFORMED_RESPONSE'", requestId);
    }

    @Test
    void timeoutRetryAfterSideEffectDoesNotDuplicateSideEffect() {
        String requestId = rid();
        // 第一次：上游已生效但响应挂起；第二次（同键重试）：正常返回并回放同一凭证
        upstream.enqueue(UpstreamKind.TIMEOUT_AFTER_SIDE_EFFECT);
        upstream.enqueue(UpstreamKind.OK);
        GatewayResponse response =
                gateway.process(requestId, "v2", "caller-1", "key-idem", v2Body());
        assertThat(response.replayed()).isFalse();
        assertThat(upstream.invocationCount()).isEqualTo(2);
        assertThat(upstream.effectCount())
                .as("retry after timeout must apply the side effect exactly once").isEqualTo(1);
        log.info("[{}] matched rule=idempotent-retry basis='attempt1 hung post-effect, attempt2 replayed; effects=1 invocations=2'",
                requestId);
    }

    @Test
    void repeatedSameKeyReplaysWithoutCallingUpstreamAgain() {
        String requestId = rid();
        GatewayResponse first = gateway.process(requestId, "v2", "caller-1", "dup-key", v2Body());
        assertThat(first.replayed()).isFalse();
        GatewayResponse second = gateway.process(requestId, "v2", "caller-1", "dup-key", v2Body());
        assertThat(second.replayed()).isTrue();
        assertThat(second.receipt()).isEqualTo(first.receipt());
        assertThat(upstream.invocationCount())
                .as("replayed request must not invoke upstream again").isEqualTo(1);
        log.info("[{}] matched rule=idempotency-replay basis='second request with same key served from store, upstream invocations=1'",
                requestId);
    }

    @Test
    void happyPathMapsVersionAndCommitsReceipt() {
        String requestId = rid();
        GatewayResponse response = gateway.process(requestId, "v1", "caller-1", "ok-key",
                "{\"customer\":\"cust-1\",\"item\":\"book\",\"qty\":3}");
        assertThat(response.version()).isEqualTo("v1");
        assertThat(response.internalOrderSummary()).isEqualTo("cust-1/book x3");
        assertThat(response.receipt().status()).isEqualTo("ACCEPTED");
        log.info("[{}] matched rule=happy-path basis='v1 mapped, rate allowed, upstream OK, receipt committed'", requestId);
    }
}
