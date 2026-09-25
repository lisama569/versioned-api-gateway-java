package com.github.lisama569.versionedapigatewayjava.web;

import com.github.lisama569.versionedapigatewayjava.common.model.OrderResult;
import com.github.lisama569.versionedapigatewayjava.service.OrderGatewayService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一入口：v1/v2/v3 调用方共用同一路径 {@code POST /api/orders}，
 * 版本通过 {@code X-API-Version} 头分期，成功/失败结构对所有版本一致。
 */
@RestController
public class GatewayController {

    private final OrderGatewayService gatewayService;

    public GatewayController(OrderGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping(path = "/api/orders",
            consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public OrderResult placeOrder(
            @RequestHeader(value = "X-Caller-Id", required = false) String callerId,
            @RequestHeader(value = "X-API-Version", required = false) String apiVersion,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) String rawBody) {
        return gatewayService.placeOrder(callerId, apiVersion, requestId, idempotencyKey, rawBody);
    }
}
