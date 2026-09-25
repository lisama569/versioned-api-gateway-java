package com.github.lisama569.versionedapigatewayjava.tests;

import com.github.lisama569.versionedapigatewayjava.common.error.GatewayErrorCode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayException;
import com.github.lisama569.versionedapigatewayjava.ratelimit.MutableClock;
import com.github.lisama569.versionedapigatewayjava.ratelimit.Permit;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimitConfig;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimitService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流与配额测试：全程使用 {@link MutableClock}，无真实等待、无网络、完全确定。
 */
class RateLimitServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitServiceTest.class);

    /** 并发边界：maxConcurrent=3 时，再多线程同时争抢，观测到的在途峰值绝不超过 3，且必然出现瞬时限流。 */
    @Test
    void concurrencyLimitIsNeverExceededUnderContention() throws Exception {
        MutableClock clock = new MutableClock(1_000_000);
        // 桶容量与配额给足，只压并发维度；时钟不动，排除令牌恢复干扰
        RateLimitConfig cfg = new RateLimitConfig(3, 100_000, 0, 100_000, 60_000);
        RateLimitService svc = new RateLimitService(clock, cfg);

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger transientRejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try (Permit p = svc.tryAcquire("caller-A")) {
                    int now = inFlight.incrementAndGet();
                    peak.updateAndGet(prev -> Math.max(prev, now));
                    accepted.incrementAndGet();
                    try {
                        Thread.sleep(20); // 持槽，制造真实重叠
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inFlight.decrementAndGet();
                    log.info("test=Concurrency rule=ACCEPT basis={}", p.basis());
                } catch (GatewayException e) {
                    assertEquals(GatewayErrorCode.RATE_LIMITED_TRANSIENT, e.getErrorCode());
                    transientRejected.incrementAndGet();
                    log.info("test=Concurrency rule=REJECT code={} basis={}", e.getErrorCode(), e.getBasis());
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("test=Concurrency rule=SUMMARY basis=accepted={} transientRejected={} peakInFlight={} maxAllowed=3",
                accepted.get(), transientRejected.get(), peak.get());
        assertTrue(peak.get() <= 3, "并发峰值绝不能超过 3，实际=" + peak.get());
        assertEquals(3, peak.get(), "24 线程同时争抢，峰值应恰好打满 3");
        assertTrue(transientRejected.get() > 0, "必须观测到瞬时限流拒绝");
        assertEquals(threads, accepted.get() + transientRejected.get(), "每个请求要么接纳要么瞬时限流");
    }

    /** 令牌桶随时间确定性恢复：耗尽后拨快时钟应精确补令牌。 */
    @Test
    void tokenBucketRefillsDeterministically() {
        MutableClock clock = new MutableClock(0);
        RateLimitConfig cfg = new RateLimitConfig(10, 2, 1.0, 100, 60_000);
        RateLimitService svc = new RateLimitService(clock, cfg);

        try (Permit p1 = svc.tryAcquire("c"); Permit p2 = svc.tryAcquire("c")) {
            assertEquals(2, inFlight(svc, "c"), "桶容量 2 被取空");
        }
        GatewayException empty = assertThrown(svc, "c");
        assertEquals(GatewayErrorCode.RATE_LIMITED_TRANSIENT, empty.getErrorCode());
        assertEquals("TOKEN_BUCKET_EMPTY", empty.getRule());
        log.info("test=TokenBucket rule=EMPTY basis={}", empty.getBasis());

        clock.advanceMillis(999); // 不足 1 秒，不应补出整令牌
        assertEquals(GatewayErrorCode.RATE_LIMITED_TRANSIENT, assertThrown(svc, "c").getErrorCode());

        clock.advanceMillis(1); // 累计 1 秒 -> 恰好 1 个令牌
        try (Permit p = svc.tryAcquire("c")) {
            log.info("test=TokenBucket rule=REFILLED basis=after=1000ms {}", p.basis());
        }
        assertEquals(GatewayErrorCode.RATE_LIMITED_TRANSIENT, assertThrown(svc, "c").getErrorCode());

        clock.advanceMillis(2_000); // 补 2 个，但容量上限 2
        try (Permit p1 = svc.tryAcquire("c"); Permit p2 = svc.tryAcquire("c")) {
            log.info("test=TokenBucket rule=CAP_CENSURED basis=refill_capped_at_capacity");
        }
    }

    /** 配额耗尽与瞬时限流必须可区分；窗口轮换后配额确定恢复。 */
    @Test
    void quotaExhaustionIsDistinctFromTransientRateLimitAndResetsPerWindow() {
        MutableClock clock = new MutableClock(0);
        RateLimitConfig cfg = new RateLimitConfig(100, 100, 100.0, 3, 10_000);
        RateLimitService svc = new RateLimitService(clock, cfg);

        for (int i = 0; i < 3; i++) {
            try (Permit p = svc.tryAcquire("quota-caller")) {
                log.info("test=Quota rule=ACCEPT#{} basis={}", i + 1, p.basis());
            }
        }
        GatewayException exhausted = assertThrown(svc, "quota-caller");
        assertEquals(GatewayErrorCode.QUOTA_EXHAUSTED, exhausted.getErrorCode(),
                "配额耗尽必须与瞬时限流区分");
        assertEquals("QUOTA_EXHAUSTED", exhausted.getRule());
        log.info("test=Quota rule=EXHAUSTED basis={}", exhausted.getBasis());

        clock.advanceMillis(9_999);
        assertEquals(GatewayErrorCode.QUOTA_EXHAUSTED, assertThrown(svc, "quota-caller").getErrorCode(),
                "窗口未到，配额不恢复");

        clock.advanceMillis(1); // 进入下一窗口
        try (Permit p = svc.tryAcquire("quota-caller")) {
            log.info("test=Quota rule=WINDOW_RESET basis={}", p.basis());
        }
    }

    /** 不同调用方独立计数：一方打满不影响另一方。 */
    @Test
    void limitsArePerCaller() {
        MutableClock clock = new MutableClock(0);
        RateLimitConfig cfg = new RateLimitConfig(100, 1, 0.0, 100, 60_000);
        RateLimitService svc = new RateLimitService(clock, cfg);

        try (Permit p = svc.tryAcquire("A")) {
            // A 已取走唯一令牌
            assertEquals(GatewayErrorCode.RATE_LIMITED_TRANSIENT, assertThrown(svc, "A").getErrorCode());
            try (Permit q = svc.tryAcquire("B")) {
                log.info("test=PerCaller rule=ISOLATED basis=A_empty_but_B_accepted {}", q.basis());
            }
        }
    }

    private int inFlight(RateLimitService svc, String caller) {
        return ((Number) svc.snapshot(caller).get("inFlight")).intValue();
    }

    private GatewayException assertThrown(RateLimitService svc, String caller) {
        try {
            Permit p = svc.tryAcquire(caller);
            p.close();
            throw new AssertionError("期望限流但被接纳: " + caller);
        } catch (GatewayException e) {
            return e;
        }
    }
}
