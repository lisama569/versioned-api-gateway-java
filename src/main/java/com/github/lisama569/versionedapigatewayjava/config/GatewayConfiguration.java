package com.github.lisama569.versionedapigatewayjava.config;

import com.github.lisama569.versionedapigatewayjava.ratelimit.Clock;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimitConfig;
import com.github.lisama569.versionedapigatewayjava.ratelimit.SystemClock;
import com.github.lisama569.versionedapigatewayjava.upstream.MockUpstreamClient;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配：真实时钟、可通过 application.properties 覆盖的限流/配额参数、本地模拟上游。
 * 默认参数：并发 3、令牌桶 5 且 1 token/s、每 60s 配额 20。
 */
@Configuration
public class GatewayConfiguration {

    @Bean
    public Clock clock() {
        return new SystemClock();
    }

    @Bean
    public RateLimitConfig rateLimitConfig(
            @Value("${gateway.ratelimit.max-concurrent:3}") int maxConcurrent,
            @Value("${gateway.ratelimit.bucket-capacity:5}") int bucketCapacity,
            @Value("${gateway.ratelimit.refill-tokens-per-second:1.0}") double refillPerSecond,
            @Value("${gateway.ratelimit.quota-per-window:20}") int quotaPerWindow,
            @Value("${gateway.ratelimit.window-millis:60000}") long windowMillis) {
        return new RateLimitConfig(maxConcurrent, bucketCapacity, refillPerSecond,
                quotaPerWindow, windowMillis);
    }

    @Bean
    public UpstreamClient upstreamClient() {
        // 明确使用本地模拟实现：无真实网络、无外部凭据
        return new MockUpstreamClient();
    }
}
