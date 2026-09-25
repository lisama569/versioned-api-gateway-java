package com.github.lisama569.versionedapigatewayjava.ratelimit;

/** 墙钟实现：测试之外使用，绝不参与确定性断言。 */
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
