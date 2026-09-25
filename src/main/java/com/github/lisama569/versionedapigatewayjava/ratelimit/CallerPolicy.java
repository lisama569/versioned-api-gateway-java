package com.github.lisama569.versionedapigatewayjava.ratelimit;

/** 单个调用方的限流与配额策略。 */
public record CallerPolicy(int burstCapacity, double refillPerSecond, long quotaPerWindow, long windowMillis) {
}
