package com.github.lisama569.versionedapigatewayjava.ratelimit;

/**
 * 每个调用方共用的限流/配额参数。
 */
public record RateLimitConfig(
        int maxConcurrent,
        int bucketCapacity,
        double refillTokensPerSecond,
        int quotaPerWindow,
        long windowMillis
) {
}
