package com.github.lisama569.versionedapigatewayjava.web;

import com.github.lisama569.versionedapigatewayjava.gateway.GatewayResponse;
import com.github.lisama569.versionedapigatewayjava.gateway.GatewayService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayController {
    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping(path = "/api/orders", consumes = "application/json", produces = "application/json")
    public GatewayResponse createOrder(@RequestHeader(name = "X-Api-Version", required = false) String version,
                                       @RequestHeader(name = "X-Caller-Id", required = false) String callerId,
                                       @RequestHeader(name = "X-Idempotency-Key", required = false) String idempotencyKey,
                                       @RequestHeader(name = "X-Degrade-Allowed", required = false,
                                               defaultValue = "false") boolean degradeAllowed,
                                       @RequestBody String rawJson) {
        String requestId = UUID.randomUUID().toString();
        return gatewayService.process(requestId, version, callerId, idempotencyKey, rawJson, degradeAllowed);
    }
}
