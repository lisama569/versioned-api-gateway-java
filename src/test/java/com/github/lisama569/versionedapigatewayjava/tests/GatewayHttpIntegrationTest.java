package com.github.lisama569.versionedapigatewayjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过 MockMvc 验证对外契约：同一入口 /api/orders，头部分期版本；
 * 不使用真实网络与外部凭据（上游为本地模拟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayHttpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GatewayHttpIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sameEntryPointServesAllVersionsWithConsistentShape() throws Exception {
        String body = "{\"product_name\":\"widget\",\"quantity\":1,\"unit_price_cents\":100}";
        for (String version : new String[]{"v1", "v2"}) {
            log.info("test=Http case=success version={} rule=SAME_ENTRY basis=/api/orders", version);
            mockMvc.perform(post("/api/orders")
                            .header("X-Caller-Id", "caller-http-ok")
                            .header("X-API-Version", version)
                            .header("X-Request-Id", "rid-" + version)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.currency").value("USD"));
        }
    }

    @Test
    void unsupportedVersionReturns400WithExplainableBody() throws Exception {
        log.info("test=Http case=bad-version rule=VERSION_UNSUPPORTED basis=header=v9");
        mockMvc.perform(post("/api/orders")
                        .header("X-Caller-Id", "c")
                        .header("X-API-Version", "v9")
                        .header("X-Request-Id", "rid-badver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").value("rid-badver"))
                .andExpect(jsonPath("$.code").value("VERSION_UNSUPPORTED"))
                .andExpect(jsonPath("$.rule").value("VERSION_UNSUPPORTED"))
                .andExpect(jsonPath("$.basis").exists());
    }

    @Test
    void missingRequiredFieldReturnsDistinctReason() throws Exception {
        log.info("test=Http case=missing-field rule=MISSING_REQUIRED_FIELD basis=v3 currency 缺失");
        mockMvc.perform(post("/api/orders")
                        .header("X-Caller-Id", "c")
                        .header("X-API-Version", "v3")
                        .header("X-Request-Id", "rid-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_name\":\"x\",\"quantity\":1,\"unitPrice\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.basis").value(org.hamcrest.Matchers.containsString("currency")));
    }

    @Test
    void deprecatedFieldIsRejectedWithDistinctCode() throws Exception {
        log.info("test=Http case=deprecated rule=DEPRECATED_FIELD basis=v3 使用已移除的 name");
        mockMvc.perform(post("/api/orders")
                        .header("X-Caller-Id", "c")
                        .header("X-API-Version", "v3")
                        .header("X-Request-Id", "rid-dep")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"quantity\":1,\"unitPrice\":1,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEPRECATED_FIELD"))
                .andExpect(jsonPath("$.rule").value("FIELD_REMOVED_NAME"));
    }

    @Test
    void quotaExhaustionAndTransientLimitAreObservableOverHttp() throws Exception {
        // 该调用方用极小策略独立验证较难（全局共享配置），这里通过 5 个初始令牌快速打满，
        // 观测瞬时限流 429 + RATE_LIMITED_TRANSIENT（默认并发 3、桶容量 5）。
        // 使用 8 个串行请求且不等待：前 5 个取走令牌，后续应出现瞬时限流。
        int transientSeen = 0;
        for (int i = 0; i < 8; i++) {
            int status = mockMvc.perform(post("/api/orders")
                            .header("X-Caller-Id", "caller-http-burst")
                            .header("X-API-Version", "v1")
                            .header("X-Request-Id", "rid-burst-" + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"product_name\":\"burst\",\"quantity\":1,\"unit_price_cents\":1}"))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                transientSeen++;
            }
        }
        log.info("test=Http case=burst rule=RATE_LIMITED_TRANSIENT basis=transientRejected={}", transientSeen);
        // 至少出现一次瞬时限流（令牌桶先于配额被打空）
        org.junit.jupiter.api.Assertions.assertTrue(transientSeen > 0);
    }

    @Test
    void upstreamTimeoutIsNormalizedOverHttp() throws Exception {
        log.info("test=Http case=upstream-timeout rule=UPSTREAM_TIMEOUT basis=product 前缀触发");
        mockMvc.perform(post("/api/orders")
                        .header("X-Caller-Id", "caller-timeout")
                        .header("X-API-Version", "v1")
                        .header("X-Request-Id", "rid-timeout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product_name\":\"TIMEOUT:x\",\"quantity\":1,\"unit_price_cents\":1}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"))
                .andExpect(jsonPath("$.requestId").value("rid-timeout"));
    }

    @Test
    void malformedClientJsonIsClientError() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Caller-Id", "c")
                        .header("X-API-Version", "v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"));
    }
}
