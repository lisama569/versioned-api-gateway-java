package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

/**
 * 熔断器可配置项。
 *
 * @param failureThreshold 观察窗口内触发熔断的失败次数阈值（超时/错误状态/畸形均计为失败）
 * @param windowMillis     失败统计的滑动观察窗口长度
 * @param cooldownMillis   熔断后进入试探前的冷却时长
 * @param halfOpenPermits  试探阶段同时放行的最大请求数
 * @param idleTtlMillis    维度状态空闲回收时间：窗口滑过且超过该时长无活动则回收
 */
public record CircuitBreakerConfig(int failureThreshold, long windowMillis, long cooldownMillis,
                                   int halfOpenPermits, long idleTtlMillis) {

    public CircuitBreakerConfig {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (windowMillis <= 0 || cooldownMillis <= 0 || idleTtlMillis <= 0) {
            throw new IllegalArgumentException("durations must be positive");
        }
        if (halfOpenPermits < 1) {
            throw new IllegalArgumentException("halfOpenPermits must be >= 1");
        }
    }

    /** 生产默认值。 */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(3, 10_000, 5_000, 1, 60_000);
    }
}
