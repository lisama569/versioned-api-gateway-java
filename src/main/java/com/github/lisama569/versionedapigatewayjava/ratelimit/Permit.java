package com.github.lisama569.versionedapigatewayjava.ratelimit;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次成功的限流获取句柄。
 *
 * <p>{@link #close()} 只释放并发槽；令牌与配额在获取时即扣除，<b>不随释放回退</b>，
 * 因此限流与配额只反映“被接纳的请求”，不会被拒绝请求污染。close 幂等。</p>
 */
public class Permit implements AutoCloseable {

    private final String callerId;
    private final Runnable release;
    private final String basis;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public Permit(String callerId, String basis, Runnable release) {
        this.callerId = callerId;
        this.basis = basis;
        this.release = release;
    }

    public String callerId() {
        return callerId;
    }

    /** 判定快照：接纳后各项计数器的值，用于日志解释“命中的规则与判定依据”。 */
    public String basis() {
        return basis;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
