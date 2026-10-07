package com.github.lisama569.versionedapigatewayjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.lisama569.versionedapigatewayjava.circuitbreaker.CircuitBreaker;
import com.github.lisama569.versionedapigatewayjava.circuitbreaker.CircuitBreakerConfig;
import com.github.lisama569.versionedapigatewayjava.circuitbreaker.CircuitBreakerRegistry;
import com.github.lisama569.versionedapigatewayjava.gateway.GatewayService;
import com.github.lisama569.versionedapigatewayjava.idempotency.IdempotencyStore;
import com.github.lisama569.versionedapigatewayjava.ratelimit.CallerPolicy;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimiter;
import com.github.lisama569.versionedapigatewayjava.upstream.SimulatedUpstreamClient;
import com.github.lisama569.versionedapigatewayjava.versioning.VersionRouter;
import com.github.lisama569.versionedapigatewayjava.web.GatewayController;
import com.github.lisama569.versionedapigatewayjava.web.GlobalExceptionHandler;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Web 层测试：standalone MockMvc，无网络无端口，验证错误码->HTTP 映射与限流响应头。 */
class ApplicationTests {
    private static final Logger log = LoggerFactory.getLogger(ApplicationTests.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter limiter = new RateLimiter(new CallerPolicy(1, 1.0d, 1, 60_000), clock::get);
        GatewayService service = new GatewayService(new VersionRouter(), limiter,
                new SimulatedUpstreamClient(), new IdempotencyStore(), 300, 2);
        mvc = MockMvcBuilders.standaloneSetup(new GatewayController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void happyPathReturns200() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2")
                        .header("X-Caller-Id", "caller-A")
                        .header("X-Idempotency-Key", "k1")
                        .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v2"))
                .andExpect(jsonPath("$.receipt.status").value("ACCEPTED"));
        log.info("[{}] matched rule=web-happy-path basis='200 with mapped v2 response'", requestId);
    }

    @Test
    void missingVersionReturns400WithUnsupportedVersion() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_VERSION"))
                .andExpect(jsonPath("$.requestId").exists());
        log.info("[{}] matched rule=web-version-routing basis='missing X-Api-Version -> 400 UNSUPPORTED_VERSION'", requestId);
    }

    @Test
    void missingRequiredFieldReturns400WithDistinctCode() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2")
                        .header("X-Caller-Id", "caller-A")
                        .header("X-Idempotency-Key", "k2")
                        .content("{\"itemName\":\"i1\",\"quantity\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELD"));
        log.info("[{}] matched rule=web-validation basis='missing customerId -> 400 MISSING_REQUIRED_FIELD'", requestId);
    }

    @Test
    void quotaExhaustionReturns429WithRetryAfter() throws Exception {
        String requestId = UUID.randomUUID().toString();
        // capacity=1 quota=1: 第一次成功，第二次必为配额耗尽（而非瞬时限流）
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Version", "v2").header("X-Caller-Id", "caller-A")
                .header("X-Idempotency-Key", "a1")
                .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":1}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2").header("X-Caller-Id", "caller-A")
                        .header("X-Idempotency-Key", "a2")
                        .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":1}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXHAUSTED"))
                .andExpect(header().exists("Retry-After"));
        log.info("[{}] matched rule=web-quota basis='2nd request after quota=1 -> 429 QUOTA_EXHAUSTED + Retry-After'",
                requestId);
    }

    @Test
    void requestWithoutOptionalHeadersWorks() throws Exception {
        String requestId = UUID.randomUUID().toString();
        // 不携带 X-Caller-Id / X-Idempotency-Key / X-Degradation-Allowed：正常请求照常工作
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2")
                        .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("v2"))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.receipt.status").value("ACCEPTED"));
        log.info("[{}] matched rule=web-optional-headers basis='no X-Caller-Id/X-Idempotency-Key -> anonymous caller, no dedup, 200'",
                requestId);
    }

    @Test
    void openCircuitReturns503DistinctFromUpstreamErrors() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc = mvcWithOpenCircuit();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2").header("X-Caller-Id", "caller-A")
                        .header("X-Idempotency-Key", "cb1")
                        .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CIRCUIT_OPEN"))
                .andExpect(header().exists("Retry-After"));
        log.info("[{}] matched rule=web-circuit-open basis='circuit OPEN -> 503 CIRCUIT_OPEN + Retry-After, "
                + "distinct from 504 UPSTREAM_TIMEOUT / 502 UPSTREAM_ERROR'", requestId);
    }

    @Test
    void openCircuitWithDegradationAllowedReturnsReadableDegradedBody() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc = mvcWithOpenCircuit();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2").header("X-Caller-Id", "caller-A")
                        .header("X-Idempotency-Key", "cb2")
                        .header("X-Degradation-Allowed", "true")
                        .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.receipt.status").value("DEGRADED"))
                .andExpect(jsonPath("$.receipt.upstreamOrderId").value("NONE"));
        log.info("[{}] matched rule=web-degradation basis='X-Degradation-Allowed:true + circuit OPEN -> 200 degraded=true, "
                + "clearly distinguishable from real success'", requestId);
    }

    /** 构造一个熔断器已打开的独立 MockMvc（直接上报阈值次失败，假时钟冻结在打开时刻）。 */
    private MockMvc mvcWithOpenCircuit() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(
                new CircuitBreakerConfig(3, 10_000, 5_000, 1, 60_000), clock::get);
        CircuitBreaker breaker = registry.get(GatewayService.UPSTREAM_DEPENDENCY);
        for (int i = 0; i < 3; i++) {
            breaker.tryAcquire();
            breaker.onFailure();
        }
        RateLimiter limiter = new RateLimiter(new CallerPolicy(10, 10.0d, 100, 60_000), clock::get);
        GatewayService service = new GatewayService(new VersionRouter(), limiter,
                new SimulatedUpstreamClient(), new IdempotencyStore(), 300, 2, registry);
        return MockMvcBuilders.standaloneSetup(new GatewayController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
