package com.github.lisama569.versionedapigatewayjava.ratelimit;

/** 测试用：可手动拨快的确定性时钟。线程安全。 */
public class MutableClock implements Clock {

    private long current;

    public MutableClock() {
        this(0L);
    }

    public MutableClock(long startMillis) {
        this.current = startMillis;
    }

    @Override
    public synchronized long nowMillis() {
        return current;
    }

    /** 把时钟向前拨 deltaMillis 毫秒（不允许回拨，保证窗口语义单调）。 */
    public synchronized void advanceMillis(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("时钟不允许回拨: " + deltaMillis);
        }
        current += deltaMillis;
    }
}
