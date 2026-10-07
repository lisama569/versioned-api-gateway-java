package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.lisama569.versionedapigatewayjava.gateway.GatewayService;
import com.github.lisama569.versionedapigatewayjava.idempotency.IdempotencyStore;
import com.github.lisama569.versionedapigatewayjava.ratelimit.CallerPolicy;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimiter;
import com.github.lisama569.versionedapigatewayjava.upstream.SimulatedUpstreamClient;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamKind;
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

/** Web 层熔断语义：CIRCUIT_OPEN -> 503 + Retry-After；X-Degrade-Allowed -> 200 降级体。 */
class CircuitBreakerWebTest {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerWebTest.class);

    private MockMvc mvc;
    private SimulatedUpstreamClient upstream;

    @BeforeEach
    void setUp() {
        AtomicLong clock = new AtomicLong(0);
        upstream = new SimulatedUpstreamClient();
        RateLimiter limiter = new RateLimiter(new CallerPolicy(100, 100.0d, 10_000, 60_000), clock::get);
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(
                new CircuitBreakerConfig(2, 10_000, 5_000, 1, 60_000), clock::get);
        GatewayService service = new GatewayService(new VersionRouter(), limiter, upstream,
                new IdempotencyStore(), registry, GatewayService.DEFAULT_DEPENDENCY, 100, 2);
        mvc = MockMvcBuilders.standaloneSetup(new GatewayController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void openCircuit() throws Exception {
        for (int i = 0; i < 2; i++) {
            upstream.enqueue(UpstreamKind.ERROR_STATUS);
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Api-Version", "v2")
                            .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":1}"))
                    .andExpect(status().isBadGateway());
        }
    }

    @Test
    void openCircuitReturns503DistinctFromUpstreamErrors() throws Exception {
        String requestId = UUID.randomUUID().toString();
        openCircuit();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2")
                        .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CIRCUIT_OPEN"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().exists("X-Retry-After-Ms"));
        log.info("[{}] matched rule=web-circuit-open basis='503 CIRCUIT_OPEN + Retry-After, "
                + "distinct from 502 UPSTREAM_ERROR / 504 UPSTREAM_TIMEOUT'", requestId);
    }

    @Test
    void degradeAllowedHeaderReturnsReadableDegradedBody() throws Exception {
        String requestId = UUID.randomUUID().toString();
        openCircuit();
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Api-Version", "v2")
                        .header("X-Degrade-Allowed", "true")
                        .content("{\"customerId\":\"c1\",\"itemName\":\"i1\",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.receipt").doesNotExist())
                .andExpect(jsonPath("$.internalOrderSummary").value("c1/i1 x1"));
        log.info("[{}] matched rule=web-degraded basis='200 with degraded=true, receipt absent, "
                + "summary readable; distinguishable from real success'", requestId);
    }
}
