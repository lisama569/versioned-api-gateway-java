package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

/**
 * 熔断器放行判定结果。
 *
 * @param permitted        是否允许真正调用上游
 * @param state            判定时刻熔断器状态
 * @param retryAfterMillis 拒绝时建议调用方多久后再试（冷却剩余时间）
 * @param basis            判定依据（用于日志与测试断言）
 */
public record CircuitBreakerDecision(boolean permitted, CircuitState state,
                                     long retryAfterMillis, String basis) {
}
