package com.github.lisama569.versionedapigatewayjava.ratelimit;

/** 限流判定结果。 */
public record LimitDecision(boolean allowed, boolean quotaExhausted, long retryAfterMillis, String basis) {
}
