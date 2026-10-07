package com.github.lisama569.versionedapigatewayjava.gateway;

import com.github.lisama569.versionedapigatewayjava.circuitbreaker.CircuitBreaker;
import com.github.lisama569.versionedapigatewayjava.circuitbreaker.CircuitBreakerConfig;
import com.github.lisama569.versionedapigatewayjava.circuitbreaker.CircuitBreakerRegistry;
import com.github.lisama569.versionedapigatewayjava.circuitbreaker.PermitDecision;
import com.github.lisama569.versionedapigatewayjava.error.ApiException;
import com.github.lisama569.versionedapigatewayjava.error.ErrorCode;
import com.github.lisama569.versionedapigatewayjava.idempotency.IdempotencyStore;
import com.github.lisama569.versionedapigatewayjava.model.InternalOrder;
import com.github.lisama569.versionedapigatewayjava.ratelimit.CallerPolicy;
import com.github.lisama569.versionedapigatewayjava.ratelimit.LimitDecision;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimiter;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamClient;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamFailureException;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamKind;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamMalformedException;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamReceipt;
import com.github.lisama569.versionedapigatewayjava.versioning.VersionAdapter;
import com.github.lisama569.versionedapigatewayjava.versioning.VersionRouter;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 网关编排：版本映射 -> 限流/配额 -> 幂等回放 -> 熔断放行判定 -> 上游调用（超时控制 + 归一化 + 限时重试）。
 * 熔断打开时：可降级请求返回可分辨的降级结果（不写入幂等记录），其余快速失败为 CIRCUIT_OPEN。
 * 每个判定环节都打印请求标识、命中规则与判定依据。
 */
@Service
public class GatewayService {
    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    /** 默认依赖维度名：本网关只有一个上游依赖。 */
    public static final String DEFAULT_DEPENDENCY = "orders-upstream";

    private final VersionRouter router;
    private final RateLimiter rateLimiter;
    private final UpstreamClient upstream;
    private final IdempotencyStore idempotencyStore;
    private final CircuitBreakerRegistry breakerRegistry;
    private final String dependencyName;
    private final long upstreamTimeoutMillis;
    private final int maxAttempts;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public GatewayService() {
        this(new VersionRouter(),
                new RateLimiter(new CallerPolicy(5, 1.0d, 100, 60_000)),
                defaultUpstream(),
                new IdempotencyStore(), 300, 2);
    }

    public GatewayService(VersionRouter router, RateLimiter rateLimiter, UpstreamClient upstream,
                          IdempotencyStore idempotencyStore, long upstreamTimeoutMillis, int maxAttempts) {
        this(router, rateLimiter, upstream, idempotencyStore,
                new CircuitBreakerRegistry(CircuitBreakerConfig.defaults(), System::currentTimeMillis),
                DEFAULT_DEPENDENCY, upstreamTimeoutMillis, maxAttempts);
    }

    public GatewayService(VersionRouter router, RateLimiter rateLimiter, UpstreamClient upstream,
                          IdempotencyStore idempotencyStore, CircuitBreakerRegistry breakerRegistry,
                          String dependencyName, long upstreamTimeoutMillis, int maxAttempts) {
        this.router = router;
        this.rateLimiter = rateLimiter;
        this.upstream = upstream;
        this.idempotencyStore = idempotencyStore;
        this.breakerRegistry = breakerRegistry;
        this.dependencyName = dependencyName;
        this.upstreamTimeoutMillis = upstreamTimeoutMillis;
        this.maxAttempts = maxAttempts;
    }

    private static UpstreamClient defaultUpstream() {
        // 生产形态使用本地模拟上游：无真实网络、无外部凭据
        com.github.lisama569.versionedapigatewayjava.upstream.SimulatedUpstreamClient sim =
                new com.github.lisama569.versionedapigatewayjava.upstream.SimulatedUpstreamClient();
        sim.enqueue(UpstreamKind.OK);
        return sim;
    }

    public GatewayResponse process(String requestId, String version, String callerId,
                                   String idempotencyKey, String rawJson) {
        return process(requestId, version, callerId, idempotencyKey, rawJson, false);
    }

    /**
     * @param degradeAllowed 熔断打开时是否允许返回降级结果（否则快速失败为 CIRCUIT_OPEN）
     */
    public GatewayResponse process(String requestId, String version, String callerId,
                                   String idempotencyKey, String rawJson, boolean degradeAllowed) {
        log.info("[{}] request received: rule=version-routing version={} caller={} idempotencyKey={}",
                requestId, version, callerId, idempotencyKey);

        // 1. 版本路由与映射
        VersionAdapter adapter = router.resolve(version);
        log.info("[{}] matched rule=version-adapter adapter={} basis='mapped to internal model'",
                requestId, adapter.version());
        InternalOrder order = adapter.adapt(rawJson);
        log.info("[{}] version mapping accepted: rule={} basis='fields mapped/validated' internal={}",
                requestId, adapter.version(), order);

        // 2. 限流与配额
        LimitDecision decision = rateLimiter.tryAcquire(callerId == null ? "anonymous" : callerId);
        log.info("[{}] matched rule=rate-limit decision={} basis='{}'",
                requestId, decision.allowed() ? "ALLOW" : "DENY", decision.basis());
        if (!decision.allowed()) {
            if (decision.quotaExhausted()) {
                throw new ApiException(ErrorCode.QUOTA_EXHAUSTED,
                        "caller quota exhausted; reset after retryAfter window",
                        decision.retryAfterMillis());
            }
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "transient rate limit; tokens will recover over time",
                    decision.retryAfterMillis());
        }

        // 3. 幂等回放
        Optional<UpstreamReceipt> replay = idempotencyStore.get(idempotencyKey);
        if (replay.isPresent()) {
            log.info("[{}] matched rule=idempotency basis='key already processed, replaying receipt' key={}",
                    requestId, idempotencyKey);
            return new GatewayResponse(requestId, adapter.version(), summary(order), true, replay.get());
        }

        // 4. 熔断放行判定：OPEN 期间不再真正打到上游
        CircuitBreaker breaker = breakerRegistry.breakerFor(dependencyName);
        PermitDecision permit = breaker.tryAcquirePermit();
        log.info("[{}] matched rule=circuit-breaker decision={} state={} basis='{}'",
                requestId, permit.allowed() ? "PERMIT" : "REJECT", permit.state(), permit.basis());
        if (!permit.allowed()) {
            if (degradeAllowed) {
                // 降级结果：可读可分辨，但不写入幂等记录——上游恢复后同键真实请求仍要生效
                log.info("[{}] matched rule=degraded-response basis='circuit {}, serving distinguishable "
                        + "degraded result; NOT committed to idempotency store'",
                        requestId, permit.state());
                return GatewayResponse.degraded(requestId, adapter.version(), summary(order));
            }
            throw new ApiException(ErrorCode.CIRCUIT_OPEN,
                    "dependency circuit is " + permit.state() + "; retry after cooldown",
                    permit.retryAfterMillis());
        }

        // 5. 上游调用（带超时与一次重试，重试携带同一幂等键，不产生重复副作用）
        // 未携带幂等键时按请求生成一次性键：保证重试仍同键、上游去重表不会遇到 null
        String upstreamKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "req-" + requestId : idempotencyKey;
        UpstreamReceipt receipt;
        try {
            receipt = invokeUpstreamWithTimeout(requestId, upstreamKey, order);
        } catch (ApiException e) {
            if (isUpstreamFailure(e.code())) {
                breaker.onFailure();
                log.info("[{}] matched rule=circuit-failure-recorded basis='{} counted for dependency {}'",
                        requestId, e.code(), dependencyName);
            }
            throw e;
        }
        breaker.onSuccess();
        idempotencyStore.putIfAbsent(idempotencyKey, receipt);
        log.info("[{}] request completed: rule=upstream-call basis='receipt committed' receipt={}",
                requestId, receipt);
        return new GatewayResponse(requestId, adapter.version(), summary(order), false, receipt);
    }

    private static boolean isUpstreamFailure(ErrorCode code) {
        return code == ErrorCode.UPSTREAM_TIMEOUT
                || code == ErrorCode.UPSTREAM_ERROR
                || code == ErrorCode.UPSTREAM_MALFORMED_RESPONSE;
    }

    private UpstreamReceipt invokeUpstreamWithTimeout(String requestId, String key, InternalOrder order) {
        TimeoutException lastTimeout = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<UpstreamReceipt> future = executor.submit(() -> upstream.submit(key, order));
            try {
                UpstreamReceipt receipt = future.get(upstreamTimeoutMillis, TimeUnit.MILLISECONDS);
                if (attempt > 1) {
                    log.info("[{}] upstream retry succeeded on attempt={}", requestId, attempt);
                }
                return receipt;
            } catch (TimeoutException e) {
                lastTimeout = e;
                future.cancel(true);
                log.warn("[{}] upstream timeout attempt={}/{} basis='future.get exceeded {}ms, retrying with same key'",
                        requestId, attempt, maxAttempts, upstreamTimeoutMillis);
            } catch (Exception e) {
                future.cancel(true);
                throw normalizeUpstreamError(requestId, rootCause(e));
            }
        }
        log.error("[{}] upstream giving up after {} attempts: code={} basis='all attempts timed out'",
                requestId, maxAttempts, ErrorCode.UPSTREAM_TIMEOUT);
        throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT,
                "upstream timed out after " + maxAttempts + " attempts");
    }

    private Throwable rootCause(Exception e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    private ApiException normalizeUpstreamError(String requestId, Throwable cause) {
        if (cause instanceof UpstreamFailureException ufe) {
            log.error("[{}] upstream error-status: code={} upstreamStatus={} basis='mapped, raw hidden'",
                    requestId, ErrorCode.UPSTREAM_ERROR, ufe.status());
            return new ApiException(ErrorCode.UPSTREAM_ERROR,
                    "upstream responded with an error status");
        }
        if (cause instanceof UpstreamMalformedException) {
            log.error("[{}] upstream malformed response: code={} basis='unparsable payload mapped, raw hidden'",
                    requestId, ErrorCode.UPSTREAM_MALFORMED_RESPONSE);
            return new ApiException(ErrorCode.UPSTREAM_MALFORMED_RESPONSE,
                    "upstream returned a malformed response");
        }
        log.error("[{}] upstream unexpected failure: code={} basis='unknown upstream exception mapped, raw hidden'",
                requestId, ErrorCode.UPSTREAM_ERROR, cause);
        return new ApiException(ErrorCode.UPSTREAM_ERROR, "upstream call failed");
    }

    private static String summary(InternalOrder o) {
        return o.customerId() + "/" + o.itemName() + " x" + o.quantity();
    }
}
