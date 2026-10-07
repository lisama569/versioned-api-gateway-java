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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 熔断与降级测试（纯本地模拟器 + 可注入假时钟，无真实网络）：
 * 熔断打开、冷却试探、恢复与再次熔断、降级结果可区分且不污染幂等记录、
 * 试探阶段并发只放行约定数量、窗口滑动与维度回收。
 */
class CircuitBreakerTest {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTest.class);

    private static final int THRESHOLD = 3;
    private static final long WINDOW_MS = 10_000;
    private static final long COOLDOWN_MS = 5_000;
    private static final int HALF_OPEN_PERMITS = 2;
    private static final long IDLE_TTL_MS = 60_000;

    private AtomicLong clock;
    private SimulatedUpstreamClient upstream;
    private IdempotencyStore idempotency;
    private CircuitBreakerRegistry registry;
    private GatewayService gateway;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0);
        upstream = new SimulatedUpstreamClient();
        idempotency = new IdempotencyStore();
        registry = new CircuitBreakerRegistry(
                new CircuitBreakerConfig(THRESHOLD, WINDOW_MS, COOLDOWN_MS, HALF_OPEN_PERMITS, IDLE_TTL_MS),
                clock::get);
        RateLimiter limiter = new RateLimiter(new CallerPolicy(100, 100.0d, 10_000, 60_000), clock::get);
        gateway = new GatewayService(new VersionRouter(), limiter, upstream, idempotency, 1_000, 2, registry);
    }

    private String rid() {
        return UUID.randomUUID().toString();
    }

    private String v2Body() {
        return "{\"customerId\":\"cust-1\",\"itemName\":\"book\",\"quantity\":1}";
    }

    private void openCircuit() {
        for (int i = 0; i < THRESHOLD; i++) {
            upstream.enqueue(UpstreamKind.ERROR_STATUS);
            int seq = i;
            assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", "open-" + seq, v2Body()))
                    .isInstanceOfSatisfying(ApiException.class, ex ->
                            assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));
        }
        assertThat(registry.get(GatewayService.UPSTREAM_DEPENDENCY).state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void circuitOpensAfterThresholdAndFastFailsWithRetryAfter() {
        String requestId = rid();
        openCircuit();
        int invocationsBefore = upstream.invocationCount();
        log.info("[{}] matched rule=circuit-open basis='{} failures within {}ms window -> OPEN' invocations={}",
                requestId, THRESHOLD, WINDOW_MS, invocationsBefore);

        // 熔断期间：不允许降级的请求快速失败，错误码与超时/错误/畸形可区分，且带 retryAfter
        assertThatThrownBy(() -> gateway.process(requestId, "v2", "caller-1", "fast-fail", v2Body()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.CIRCUIT_OPEN);
                    assertThat(ex.retryAfterMillis()).isNotNull().isPositive().isLessThanOrEqualTo(COOLDOWN_MS);
                });
        assertThat(upstream.invocationCount())
                .as("open circuit must not hit upstream").isEqualTo(invocationsBefore);
        log.info("[{}] matched rule=circuit-fast-fail basis='CIRCUIT_OPEN distinct from TIMEOUT/ERROR/MALFORMED, "
                + "retryAfter<=cooldown, upstream untouched'", requestId);
    }

    @Test
    void halfOpenProbePermitsAreStrictlyLimitedUnderConcurrency() throws Exception {
        String requestId = rid();
        openCircuit();
        clock.addAndGet(COOLDOWN_MS + 1); // 冷却期满

        CircuitBreaker breaker = registry.get(GatewayService.UPSTREAM_DEPENDENCY);
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger permitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    CircuitBreakerDecision d = breaker.tryAcquire();
                    if (d.permitted()) {
                        permitted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        log.info("[{}] matched rule=half-open-permits basis='{} concurrent probes, halfOpenPermits={}' permitted={} rejected={}",
                requestId, threads, HALF_OPEN_PERMITS, permitted.get(), rejected.get());
        assertThat(permitted.get())
                .as("half-open must never grant more than halfOpenPermits concurrently")
                .isEqualTo(HALF_OPEN_PERMITS);
        assertThat(rejected.get()).isEqualTo(threads - HALF_OPEN_PERMITS);
    }

    @Test
    void gatewayLevelConcurrentProbesOnlyPermitConfiguredNumber() throws Exception {
        String requestId = rid();
        openCircuit();
        clock.addAndGet(COOLDOWN_MS + 1);
        // 试探请求变慢（200ms），保证并发请求到达时试探仍在途
        upstream.setSimulatedLatencyMillis(200);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger circuitRejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            pool.submit(() -> {
                try {
                    start.await();
                    gateway.process(rid(), "v2", "caller-1", "probe-" + seq, v2Body());
                    succeeded.incrementAndGet();
                } catch (ApiException e) {
                    if (e.code() == ErrorCode.CIRCUIT_OPEN) {
                        circuitRejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        log.info("[{}] matched rule=gateway-half-open-concurrency basis='10 concurrent after cooldown, "
                        + "permits={}' upstreamInvocations={} succeeded={} circuitRejected={}",
                requestId, HALF_OPEN_PERMITS, upstream.invocationCount(), succeeded.get(), circuitRejected.get());
        assertThat(upstream.invocationCount())
                .as("only the permitted probes may reach upstream")
                .isEqualTo(THRESHOLD + HALF_OPEN_PERMITS);
        assertThat(succeeded.get()).isEqualTo(HALF_OPEN_PERMITS);
        assertThat(circuitRejected.get()).isEqualTo(threads - HALF_OPEN_PERMITS);
        // 试探成功后熔断器应已恢复关闭
        assertThat(registry.get(GatewayService.UPSTREAM_DEPENDENCY).state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void probeSuccessClosesCircuitAndProbeFailureReopens() {
        String requestId = rid();
        openCircuit();
        clock.addAndGet(COOLDOWN_MS + 1);

        // 冷却期满 -> 试探放行；上游恢复 -> 成功 -> 关闭
        upstream.enqueue(UpstreamKind.OK);
        GatewayResponse probe = gateway.process(requestId, "v2", "caller-1", "probe-ok", v2Body());
        assertThat(probe.degraded()).isFalse();
        assertThat(registry.get(GatewayService.UPSTREAM_DEPENDENCY).state()).isEqualTo(CircuitState.CLOSED);
        log.info("[{}] matched rule=half-open-recover basis='probe succeeded -> CLOSED, failures cleared'", requestId);

        // 恢复后正常流量放行
        GatewayResponse normal = gateway.process(rid(), "v2", "caller-1", "after-recover", v2Body());
        assertThat(normal.degraded()).isFalse();

        // 再次连续失败 -> 再次熔断
        openCircuit();
        log.info("[{}] matched rule=circuit-reopen basis='{} fresh failures after recovery -> OPEN again'",
                requestId, THRESHOLD);

        // 冷却期满后试探失败 -> 保持熔断，冷却重新计时
        clock.addAndGet(COOLDOWN_MS + 1);
        upstream.enqueue(UpstreamKind.ERROR_STATUS);
        assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", "probe-fail", v2Body()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR));
        CircuitBreaker breaker = registry.get(GatewayService.UPSTREAM_DEPENDENCY);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        log.info("[{}] matched rule=probe-failure-reopen basis='half-open probe failed -> OPEN, cooldown restarted'",
                requestId);

        // 冷却未满时仍快速失败；再推进完整冷却后才允许下一次试探
        clock.addAndGet(COOLDOWN_MS - 1);
        assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", "still-open", v2Body()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo(ErrorCode.CIRCUIT_OPEN));
        log.info("[{}] matched rule=cooldown-restart basis='1ms before new cooldown end -> still CIRCUIT_OPEN'",
                requestId);
    }

    @Test
    void degradedResponseIsDistinguishableAndNotCommittedToIdempotency() {
        String requestId = rid();
        openCircuit();

        // 可降级请求：返回可分辨的降级结果，而非错误
        GatewayResponse degraded =
                gateway.process(requestId, "v2", "caller-1", "deg-key", v2Body(), true);
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.receipt().status()).isEqualTo("DEGRADED");
        assertThat(degraded.receipt().upstreamOrderId()).isEqualTo("NONE");
        assertThat(degraded.replayed()).isFalse();
        log.info("[{}] matched rule=degradation basis='degraded=true receipt=DEGRADED/NONE, clearly not a real success'",
                requestId);

        // 降级结果不得进入幂等记录
        assertThat(idempotency.get("deg-key"))
                .as("degraded result must not be committed to idempotency store")
                .isEmpty();
        int effectsBefore = upstream.effectCount();

        // 上游恢复后：同一幂等键真实请求真正生效且只生效一次
        clock.addAndGet(COOLDOWN_MS + 1);
        upstream.enqueue(UpstreamKind.OK);
        GatewayResponse real = gateway.process(rid(), "v2", "caller-1", "deg-key", v2Body(), true);
        assertThat(real.degraded()).isFalse();
        assertThat(real.receipt().status()).isEqualTo("ACCEPTED");
        assertThat(upstream.effectCount())
                .as("real request after degradation must apply side effect exactly once")
                .isEqualTo(effectsBefore + 1);
        log.info("[{}] matched rule=degradation-no-idempotency-pollution basis='same key really executed once after recovery' effects={}",
                requestId, upstream.effectCount());

        // 同键再次请求 -> 幂等回放真实凭证，不再调用上游
        int invocationsBefore = upstream.invocationCount();
        GatewayResponse replay = gateway.process(rid(), "v2", "caller-1", "deg-key", v2Body(), true);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receipt()).isEqualTo(real.receipt());
        assertThat(upstream.invocationCount()).isEqualTo(invocationsBefore);
        assertThat(upstream.effectCount()).isEqualTo(effectsBefore + 1);
        log.info("[{}] matched rule=idempotency-replay-after-degradation basis='replay serves real receipt, no extra side effect'",
                requestId);
    }

    @Test
    void failuresOutsideWindowDoNotTripAndIdleDimensionsAreEvicted() {
        String requestId = rid();
        CircuitBreaker breaker = registry.get(GatewayService.UPSTREAM_DEPENDENCY);

        // 窗口内 2 次失败（阈值 3），窗口滑过后再 1 次失败：旧失败已过期，不熔断
        upstream.enqueue(UpstreamKind.ERROR_STATUS);
        upstream.enqueue(UpstreamKind.ERROR_STATUS);
        for (int i = 0; i < 2; i++) {
            int seq = i;
            assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", "w-" + seq, v2Body()))
                    .isInstanceOf(ApiException.class);
        }
        assertThat(breaker.failuresInWindow()).isEqualTo(2);
        clock.addAndGet(WINDOW_MS + 1); // 观察窗口滑过
        assertThat(breaker.failuresInWindow())
                .as("failures older than the window must expire").isZero();

        upstream.enqueue(UpstreamKind.ERROR_STATUS);
        assertThatThrownBy(() -> gateway.process(rid(), "v2", "caller-1", "w-2", v2Body()))
                .isInstanceOf(ApiException.class);
        assertThat(breaker.state())
                .as("1 fresh failure after window slid must not trip the circuit (threshold=3)")
                .isEqualTo(CircuitState.CLOSED);
        log.info("[{}] matched rule=sliding-window basis='2 old failures expired after {}ms, 1 fresh < threshold {} -> CLOSED'",
                requestId, WINDOW_MS, THRESHOLD);

        // 维度空闲回收：推进超过 idleTtl 后访问任意维度，空闲维度被回收
        assertThat(registry.dimensionCount()).isEqualTo(1);
        clock.addAndGet(IDLE_TTL_MS + 1);
        registry.get("another-dependency"); // 触发回收
        assertThat(registry.dimensionCount())
                .as("idle dimension must be evicted, only the fresh one remains")
                .isEqualTo(1);
        assertThat(registry.get(GatewayService.UPSTREAM_DEPENDENCY).state())
                .as("evicted dimension restarts from a clean CLOSED state")
                .isEqualTo(CircuitState.CLOSED);
        log.info("[{}] matched rule=idle-eviction basis='dimension idle > {}ms evicted; state count bounded'",
                requestId, IDLE_TTL_MS);
    }

    @Test
    void perDependencyDimensionsAreIndependent() {
        String requestId = rid();
        CircuitBreaker orders = registry.get("orders-upstream");
        CircuitBreaker inventory = registry.get("inventory-upstream");
        for (int i = 0; i < THRESHOLD; i++) {
            orders.tryAcquire();
            orders.onFailure();
        }
        assertThat(orders.state()).isEqualTo(CircuitState.OPEN);
        assertThat(inventory.state())
                .as("failure statistics are kept per dependency dimension")
                .isEqualTo(CircuitState.CLOSED);
        assertThat(inventory.tryAcquire().permitted()).isTrue();
        log.info("[{}] matched rule=per-dimension basis='orders-upstream OPEN, inventory-upstream unaffected'",
                requestId);
    }
}
