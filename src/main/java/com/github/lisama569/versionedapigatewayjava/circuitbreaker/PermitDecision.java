package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

/**
 * 熔断器放行判定。
 *
 * @param allowed          是否放行本次请求真正调用上游
 * @param state            判定时刻的熔断器状态
 * @param retryAfterMillis 未放行时建议的重试等待时间（冷却剩余），放行时为 0
 * @param basis            判定依据（用于日志与测试追溯）
 */
public record PermitDecision(boolean allowed, CircuitState state, long retryAfterMillis, String basis) {
}
