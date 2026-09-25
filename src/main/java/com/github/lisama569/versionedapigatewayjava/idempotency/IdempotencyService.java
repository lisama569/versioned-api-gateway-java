package com.github.lisama569.versionedapigatewayjava.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * 幂等控制：同一个幂等键对应的上游副作用<b>最多发生一次</b>。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>首个请求执行 action，成功结果与失败异常都会被记录；</li>
 *   <li>action 在途时的并发同键请求等待并共享同一个结果，不会再触发一次上游调用；</li>
 *   <li>完成后的重复/重试请求直接返回缓存结果（成功）或重放同一失败（不重复副作用）；</li>
 *   <li>为防止“在途持锁线程再次入店”的自锁死，同线程重入时直接执行 action。</li>
 * </ul>
 * 该服务只保证上游副作用去重；每次 HTTP 请求仍独立经过限流/配额（由上层编排）。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public <T> T execute(String key, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            // 没有幂等键：每次都是独立调用（调用方自行承担重复提交风险）
            log.debug("idempotency decision=EXECUTE_NO_KEY");
            return action.get();
        }
        Entry entry;
        boolean leader;
        synchronized (store) {
            Entry existing = store.get(key);
            if (existing == null) {
                entry = new Entry();
                store.put(key, entry);
                leader = true;
            } else if (existing.leaderThread == Thread.currentThread()) {
                // 同线程重入：不能等待自己，直接执行
                log.debug("idempotency decision=REENTRANT_EXECUTE key={}", key);
                return action.get();
            } else {
                entry = existing;
                leader = false;
            }
        }

        if (leader) {
            T value;
            try {
                value = action.get();
            } catch (RuntimeException e) {
                synchronized (entry) {
                    entry.future.completeExceptionally(e);
                    entry.leaderThread = null;
                    entry.notifyAll();
                }
                log.info("idempotency decision=FAILED key={} error={}", key, e.getClass().getSimpleName());
                throw e;
            }
            synchronized (entry) {
                entry.future.complete(value);
                entry.leaderThread = null;
                entry.notifyAll();
            }
            log.info("idempotency decision=COMPLETED key={}", key);
            return value;
        }

        // 跟随者：等待 leader 结果并共享
        synchronized (entry) {
            while (!entry.future.isDone()) {
                try {
                    entry.wait();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待幂等结果被中断 key=" + key, ie);
                }
            }
        }
        log.info("idempotency decision=SHARED key={}", key);
        try {
            @SuppressWarnings("unchecked")
            T shared = (T) entry.future.join();
            return shared;
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw ce;
        }
    }

    /** 仅供测试：清空全部幂等记录。 */
    public void reset() {
        store.clear();
    }

    private static final class Entry {
        private final CompletableFuture<Object> future = new CompletableFuture<>();
        private volatile Thread leaderThread;

        private Entry() {
            this.leaderThread = Thread.currentThread();
        }
    }
}
