package com.github.lisama569.versionedapigatewayjava.tests;

import com.github.lisama569.versionedapigatewayjava.idempotency.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等语义测试：本地并发，无网络。
 */
class IdempotencyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceTest.class);

    @Test
    void sameKeyExecutesActionExactlyOnceEvenUnderConcurrency() throws Exception {
        IdempotencyService svc = new IdempotencyService();
        AtomicInteger sideEffects = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String r = svc.execute("order-key-1", () -> {
                    int n = sideEffects.incrementAndGet();
                    inside.countDown();
                    try {
                        release.await(); // 让其余线程都在等待结果
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "RESULT";
                });
                assertEquals("RESULT", r);
                ok.incrementAndGet();
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("test=Idempotency rule=EXACTLY_ONCE basis=threads={} sideEffects={} allGotResult={}",
                threads, sideEffects.get(), ok.get() == threads);
        assertEquals(1, sideEffects.get(), "并发同键上游副作用必须恰好一次");
        assertEquals(threads, ok.get(), "所有并发请求共享同一结果");
    }

    @Test
    void failureIsReplayedWithoutReExecuting() {
        IdempotencyService svc = new IdempotencyService();
        AtomicInteger calls = new AtomicInteger();

        Runnable once = () -> svc.execute("fail-key", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, once::run);
        assertThrows(IllegalStateException.class, once::run);
        log.info("test=Idempotency rule=FAILURE_REPLAY basis=calls={} (重放失败不再触发副作用)", calls.get());
        assertEquals(1, calls.get(), "失败结果被缓存并重放，不重复执行 action");
    }

    @Test
    void missingKeyMeansIndependentExecutions() {
        IdempotencyService svc = new IdempotencyService();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            svc.execute(null, calls::incrementAndGet);
        }
        log.info("test=Idempotency rule=NO_KEY_INDEPENDENT basis=calls={}", calls.get());
        assertEquals(3, calls.get());
    }
}
