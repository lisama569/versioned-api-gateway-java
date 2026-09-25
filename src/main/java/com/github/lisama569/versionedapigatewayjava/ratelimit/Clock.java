package com.github.lisama569.versionedapigatewayjava.ratelimit;

/** 可注入时钟，使令牌恢复与窗口轮换在测试中完全确定。 */
public interface Clock {
    long nowMillis();
}
