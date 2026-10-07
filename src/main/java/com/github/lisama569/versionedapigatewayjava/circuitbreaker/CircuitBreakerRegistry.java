package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按依赖维度维护熔断器（骨架，实现后续填充）：
 * 每个维度独立统计，空闲超过 stateTtlMillis 的状态可被回收，避免无上限堆积。
 */
public class CircuitBreakerRegistry {
    private final CircuitBreakerConfig config;
    private final LongSupplier nowMillis;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(CircuitBreakerConfig config, LongSupplier nowMillis) {
        this.config = config;
        this.nowMillis = nowMillis;
    }

    /**
     * 获取（或惰性创建）某依赖维度的熔断器。获取前顺带回收空闲过期状态，
     * 窗口滑过且长期无活动的维度不会无上限堆积。
     */
    public CircuitBreaker breakerFor(String dependency) {
        reapIdle();
        return breakers.computeIfAbsent(dependency, d -> new CircuitBreaker(d, config, nowMillis));
    }

    /** 回收空闲超过 stateTtlMillis 的维度状态，返回回收数量。 */
    public int reapIdle() {
        long now = nowMillis.getAsLong();
        int[] reaped = {0};
        breakers.entrySet().removeIf(e -> {
            boolean idle = now - e.getValue().lastActivityMillis() >= config.stateTtlMillis();
            if (idle) {
                reaped[0]++;
            }
            return idle;
        });
        return reaped[0];
    }

    public int size() {
        return breakers.size();
    }
}
