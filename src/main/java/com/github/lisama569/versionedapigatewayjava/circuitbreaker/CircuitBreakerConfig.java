package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

/**
 * 熔断器可配置项。
 *
 * @param failureThreshold 观察窗口内触发熔断的失败次数阈值
 * @param windowMillis     失败统计的滑动观察窗口（毫秒）
 * @param cooldownMillis   熔断后进入试探前的冷却时长（毫秒）
 * @param halfOpenPermits  半开状态同时放行的试探请求数上限
 * @param stateTtlMillis   维度状态空闲多久后可被回收（毫秒）
 */
public record CircuitBreakerConfig(int failureThreshold, long windowMillis, long cooldownMillis,
                                   int halfOpenPermits, long stateTtlMillis) {

    public CircuitBreakerConfig {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (windowMillis <= 0 || cooldownMillis <= 0 || stateTtlMillis <= 0) {
            throw new IllegalArgumentException("durations must be positive");
        }
        if (halfOpenPermits < 1) {
            throw new IllegalArgumentException("halfOpenPermits must be >= 1");
        }
    }

    /** 宽松默认值：默认构造的网关不因少量失败误熔断。 */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(5, 10_000, 30_000, 1, 600_000);
    }
}
