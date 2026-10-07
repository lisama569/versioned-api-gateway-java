package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按依赖维度维护熔断器实例：
 * - 每个上游依赖（按名称）独立统计失败与状态迁移，互不影响；
 * - 访问时顺带回收：维度空闲超过 idleTtlMillis（观察窗口早已滑过）即移除，
 *   长期运行不会无上限堆积；
 * - 时间源可注入，回收判定使用同一时钟，测试可确定性验证。
 */
public class CircuitBreakerRegistry {
    private final CircuitBreakerConfig config;
    private final LongSupplier nowMillis;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(CircuitBreakerConfig config, LongSupplier nowMillis) {
        this.config = config;
        this.nowMillis = nowMillis;
    }

    /** 获取（或创建）某依赖维度的熔断器，并顺带回收空闲维度。 */
    public CircuitBreaker get(String dependency) {
        evictIdle();
        return breakers.computeIfAbsent(dependency, d -> new CircuitBreaker(d, config, nowMillis));
    }

    /** 回收空闲超过 idleTtlMillis 的维度状态。 */
    private void evictIdle() {
        long now = nowMillis.getAsLong();
        breakers.entrySet().removeIf(e -> now - e.getValue().lastTouchedMillis() >= config.idleTtlMillis());
    }

    /** 当前维护的维度数（测试用）。 */
    public int dimensionCount() {
        return breakers.size();
    }
}
