package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 熔断器状态机单测（假时钟，无真实时间等待）：
 * 打开 -> 冷却 -> 半开试探 -> 恢复 / 再次熔断；半开并发放行严格受限；空闲状态可回收。
 */
class CircuitBreakerTest {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTest.class);

    private AtomicLong clock;
    private CircuitBreakerConfig config;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0);
        // 阈值 3，窗口 10s，冷却 5s，半开放行 2，状态 TTL 60s
        config = new CircuitBreakerConfig(3, 10_000, 5_000, 2, 60_000);
        breaker = new CircuitBreaker("dep-A", config, clock::get);
    }

    private String rid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void opensAfterFailureThresholdWithinWindow() {
        String requestId = rid();
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        PermitDecision stillOpen = breaker.tryAcquirePermit();
        assertThat(stillOpen.allowed()).as("below threshold must pass").isTrue();

        breaker.onFailure(); // 第 3 次失败，达到阈值
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        PermitDecision rejected = breaker.tryAcquirePermit();
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.state()).isEqualTo(CircuitState.OPEN);
        assertThat(rejected.retryAfterMillis()).isEqualTo(5_000);
        log.info("[{}] matched rule=circuit-open basis='3 failures within 10000ms window -> OPEN, "
                + "rejected with retryAfterMs=5000 (cooldown remaining)'", requestId);
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        String requestId = rid();
        breaker.onFailure();
        breaker.onFailure();
        clock.addAndGet(10_001); // 窗口滑过，旧失败被回收
        breaker.onFailure();
        assertThat(breaker.failuresInWindow()).isEqualTo(1);
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        log.info("[{}] matched rule=sliding-window basis='2 stale failures evicted after window slid, "
                + "only 1 in window -> still CLOSED'", requestId);
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() {
        String requestId = rid();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        clock.addAndGet(4_999);
        assertThat(breaker.tryAcquirePermit().allowed())
                .as("cooldown not elapsed yet").isFalse();

        clock.addAndGet(1); // 冷却满 5000ms
        PermitDecision probe = breaker.tryAcquirePermit();
        assertThat(probe.allowed()).isTrue();
        assertThat(probe.state()).isEqualTo(CircuitState.HALF_OPEN);

        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.failuresInWindow()).isZero();
        log.info("[{}] matched rule=half-open-recover basis='cooldown 5000ms elapsed -> HALF_OPEN, "
                + "probe succeeded -> CLOSED, failure window cleared'", requestId);
    }

    @Test
    void halfOpenProbeFailureReopensAndRecoveryCanBreakAgain() {
        String requestId = rid();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        clock.addAndGet(5_000);
        assertThat(breaker.tryAcquirePermit().state()).isEqualTo(CircuitState.HALF_OPEN);

        breaker.onFailure(); // 试探失败 -> 重新熔断，冷却重启
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        PermitDecision rejected = breaker.tryAcquirePermit();
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterMillis()).isEqualTo(5_000);
        log.info("[{}] matched rule=half-open-reopen basis='probe failed -> OPEN again, cooldown restarts'",
                requestId);

        // 再次冷却 -> 试探成功 -> 恢复；恢复后再次累计失败 -> 再次熔断
        clock.addAndGet(5_000);
        breaker.tryAcquirePermit();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        log.info("[{}] matched rule=rebreak-after-recovery basis='recovered then 3 fresh failures -> OPEN again'",
                requestId);
    }

    @Test
    void halfOpenPermitsStrictlyBoundedUnderConcurrency() throws Exception {
        String requestId = rid();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        clock.addAndGet(5_000); // 进入 HALF_OPEN，permits=2

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PermitDecision>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return breaker.tryAcquirePermit();
            }));
        }
        ready.await();
        go.countDown();
        int allowed = 0;
        int rejectedSaturated = 0;
        for (Future<PermitDecision> f : futures) {
            PermitDecision d = f.get();
            if (d.allowed()) {
                allowed++;
            } else {
                rejectedSaturated++;
                assertThat(d.state()).isEqualTo(CircuitState.HALF_OPEN);
            }
        }
        pool.shutdown();
        assertThat(allowed).as("half-open permits must be strictly bounded").isEqualTo(2);
        assertThat(rejectedSaturated).isEqualTo(threads - 2);
        log.info("[{}] matched rule=half-open-concurrency basis='{} threads raced for {} probe slots, "
                + "allowed={} rejected={} (no overshoot)'", requestId, threads, 2, allowed, rejectedSaturated);
    }

    @Test
    void registryReapsIdleDimensionState() {
        String requestId = rid();
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(config, clock::get);
        registry.breakerFor("dep-hot").onFailure();
        registry.breakerFor("dep-cold");
        assertThat(registry.size()).isEqualTo(2);

        clock.addAndGet(60_001); // 两个维度都空闲超过 TTL
        int reaped = registry.reapIdle();
        assertThat(reaped).isEqualTo(2);
        assertThat(registry.size()).isZero();

        // 回收后重建为全新 CLOSED 状态，不残留旧失败
        CircuitBreaker fresh = registry.breakerFor("dep-hot");
        assertThat(fresh.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(fresh.failuresInWindow()).isZero();
        log.info("[{}] matched rule=state-reclaim basis='2 dimensions idle beyond ttlMs=60000 reaped, "
                + "re-created fresh without stale failures'", requestId);
    }
}
