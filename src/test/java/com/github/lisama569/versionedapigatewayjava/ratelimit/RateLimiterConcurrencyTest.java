package com.github.lisama569.versionedapigatewayjava.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 并发限流边界测试：时间源为可注入假时钟，不依赖真实时间/网络。
 * 覆盖：并发不超发、令牌确定性恢复、瞬时限流与配额耗尽可区分。
 */
class RateLimiterConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterConcurrencyTest.class);

    @Test
    void concurrentBurstMustNotExceedCapacity() throws Exception {
        String requestId = UUID.randomUUID().toString();
        AtomicLong clock = new AtomicLong(1_000);
        CallerPolicy policy = new CallerPolicy(5, 2.0d, 10_000, 60_000);
        RateLimiter limiter = new RateLimiter(policy, clock::get);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger transientDenied = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LimitDecision d = limiter.tryAcquire("caller-A");
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else if (!d.quotaExhausted()) {
                        transientDenied.incrementAndGet();
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

        log.info("[{}] matched rule=token-burst basis='50 concurrent callers, capacity=5, frozen clock' accepted={} transientDenied={}",
                requestId, allowed.get(), transientDenied.get());

        assertThat(allowed.get()).as("concurrent burst must never exceed burst capacity").isEqualTo(5);
        assertThat(transientDenied.get()).isEqualTo(45);
    }

    @Test
    void tokensRecoverDeterministicallyOverTime() {
        String requestId = UUID.randomUUID().toString();
        AtomicLong clock = new AtomicLong(0);
        CallerPolicy policy = new CallerPolicy(1, 1.0d, 10_000, 60_000);
        RateLimiter limiter = new RateLimiter(policy, clock::get);

        assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        LimitDecision denied = limiter.tryAcquire("c");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.quotaExhausted()).isFalse();
        log.info("[{}] matched rule=token-refill basis='capacity exhausted at t=0, 1 token/sec' retryAfterMs={}",
                requestId, denied.retryAfterMillis());

        clock.addAndGet(500);
        LimitDecision half = limiter.tryAcquire("c");
        assertThat(half.allowed()).as("500ms refills only 0.5 token, still denied").isFalse();

        clock.addAndGet(500);
        LimitDecision recovered = limiter.tryAcquire("c");
        assertThat(recovered.allowed()).as("1000ms later exactly 1 token restored").isTrue();
        log.info("[{}] matched rule=token-refill basis='advanced 1000ms total, 1 token available' accepted=true",
                requestId);
    }

    @Test
    void quotaExhaustionIsDistinguishableFromTransientLimit() {
        String requestId = UUID.randomUUID().toString();
        AtomicLong clock = new AtomicLong(0);
        CallerPolicy policy = new CallerPolicy(10, 10.0d, 3, 60_000);
        RateLimiter limiter = new RateLimiter(policy, clock::get);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        }
        LimitDecision quota = limiter.tryAcquire("c");
        assertThat(quota.allowed()).isFalse();
        assertThat(quota.quotaExhausted())
                .as("denial must be flagged as quota exhaustion, not transient throttling").isTrue();
        log.info("[{}] matched rule=quota-window basis='3/3 used in 60000ms window' retryAfterMs={}",
                requestId, quota.retryAfterMillis());

        clock.addAndGet(60_001);
        LimitDecision nextWindow = limiter.tryAcquire("c");
        assertThat(nextWindow.allowed())
                .as("after the quota window rolls over, requests are accepted again").isTrue();
        log.info("[{}] matched rule=quota-window basis='window reset after 60001ms' accepted=true", requestId);
    }

    @Test
    void perCallerBucketsAreIndependent() {
        String requestId = UUID.randomUUID().toString();
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(new CallerPolicy(1, 0.0d, 1, 60_000), clock::get);

        assertThat(limiter.tryAcquire("A").allowed()).isTrue();
        assertThat(limiter.tryAcquire("A").allowed()).isFalse();
        assertThat(limiter.tryAcquire("B").allowed()).as("caller B has an independent bucket").isTrue();
        log.info("[{}] matched rule=per-caller-buckets basis='A exhausted, B independent accepted'", requestId);
    }
}
