package com.github.lisama569.versionedapigatewayjava.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayErrorCode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayException;
import com.github.lisama569.versionedapigatewayjava.common.model.InternalOrder;
import com.github.lisama569.versionedapigatewayjava.common.model.OrderResult;
import com.github.lisama569.versionedapigatewayjava.common.model.RequestContext;
import com.github.lisama569.versionedapigatewayjava.idempotency.IdempotencyService;
import com.github.lisama569.versionedapigatewayjava.ratelimit.Permit;
import com.github.lisama569.versionedapigatewayjava.ratelimit.RateLimitService;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamClient;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamException;
import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamResponse;
import com.github.lisama569.versionedapigatewayjava.version.ApiVersion;
import com.github.lisama569.versionedapigatewayjava.version.OrderVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 编排一次下单请求：
 * <pre>
 *   版本识别/拒绝 -> 请求体解析 -> 版本字段映射 -> 限流/配额
 *     -> 幂等包裹的上游调用（503 安全重试一次） -> 响应归一化
 * </pre>
 * 任何上游异常都在此处被翻译为 {@link GatewayException}，不会原样外泄。
 */
@Service
public class OrderGatewayService {

    private static final Logger log = LoggerFactory.getLogger(OrderGatewayService.class);

    private final OrderVersionMapper versionMapper;
    private final RateLimitService rateLimitService;
    private final IdempotencyService idempotencyService;
    private final UpstreamClient upstreamClient;
    private final ObjectMapper objectMapper;

    public OrderGatewayService(OrderVersionMapper versionMapper,
                               RateLimitService rateLimitService,
                               IdempotencyService idempotencyService,
                               UpstreamClient upstreamClient,
                               ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.rateLimitService = rateLimitService;
        this.idempotencyService = idempotencyService;
        this.upstreamClient = upstreamClient;
        this.objectMapper = objectMapper;
    }

    public OrderResult placeOrder(String rawCallerId, String rawVersion, String rawRequestId,
                                  String rawIdempotencyKey, String rawBody) {
        String requestId = (rawRequestId == null || rawRequestId.isBlank())
                ? "req-" + UUID.randomUUID() : rawRequestId.trim();

        // 1) 版本：不支持/缺失即拒绝，原因可区分
        ApiVersion version = ApiVersion.fromHeader(rawVersion).orElseThrow(() -> {
            String basis = "header=X-API-Version, actual=" + rawVersion + ", 支持的版本=v1,v2,v3";
            log.warn("requestId={} rule=VERSION_UNSUPPORTED basis={}", requestId, basis);
            return new GatewayException(GatewayErrorCode.VERSION_UNSUPPORTED, "VERSION_UNSUPPORTED", basis, requestId);
        });

        String callerId = (rawCallerId == null || rawCallerId.isBlank())
                ? RateLimitService.ANONYMOUS_CALLER : rawCallerId.trim();
        RequestContext ctx = new RequestContext(requestId, callerId, version);
        log.info("requestId={} caller={} version={} rule=REQUEST_ACCEPTED basis=headers-parsed",
                requestId, callerId, version.displayName());

        // 2) 请求体解析（调用方自己的畸形 JSON 属于 4xx）
        JsonNode body = parseClientBody(requestId, rawBody);

        // 3) 版本映射：字段新增/重命名/废弃/必填规则全部在这里给出可解释拒绝
        String idempotencyKey = (rawIdempotencyKey == null || rawIdempotencyKey.isBlank())
                ? null : rawIdempotencyKey.trim();
        InternalOrder order = versionMapper.map(version, body, idempotencyKey);

        // 4) 限流/配额：失败抛瞬时限流或配额耗尽，Permit 在 finally 释放并发槽
        Permit permit;
        try {
            permit = rateLimitService.tryAcquire(callerId);
        } catch (GatewayException e) {
            // 补充请求标识后原样重抛，错误码/规则/判定依据不变
            throw new GatewayException(e.getErrorCode(), e.getRule(), e.getBasis(), requestId);
        }
        log.info("requestId={} caller={} rule=RATE_LIMIT_PASSED basis={}",
                requestId, callerId, permit.basis());
        try {
            // 5) 幂等包裹上游调用：同一 key 上游副作用最多一次；重复/并发/内部重试共享
            return idempotencyService.execute(idempotencyKey, () -> callUpstream(requestId, order));
        } finally {
            permit.close();
        }
    }

    private JsonNode parseClientBody(String requestId, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw reject(requestId, GatewayErrorCode.MISSING_REQUIRED_FIELD,
                    "REQUEST_BODY_EMPTY", "请求体为空");
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            if (node == null || !node.isObject()) {
                throw reject(requestId, GatewayErrorCode.INVALID_FIELD_VALUE,
                        "REQUEST_BODY_NOT_OBJECT", "请求体必须是 JSON 对象");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw reject(requestId, GatewayErrorCode.INVALID_FIELD_VALUE,
                    "REQUEST_BODY_MALFORMED",
                    "请求体不是合法 JSON: " + e.getOriginalMessage());
        }
    }

    /**
     * 调用上游并做错误归一化。
     *
     * <p>内部重试策略：仅对 503（上游明确“临时不可用”，且本接口为幂等键保护的下单）
     * 重试<b>一次</b>；超时、500、畸形响应一律不重试。重复提交由幂等服务去重，
     * 因此“网关内部重试”不会造成重复副作用。</p>
     */
    private OrderResult callUpstream(String requestId, InternalOrder order) {
        UpstreamResponse response;
        try {
            response = upstreamClient.invoke(order);
        } catch (UpstreamException e) {
            throw normalizeUpstreamException(requestId, e);
        } catch (RuntimeException e) {
            // 非受控异常也绝不原样外泄
            log.error("requestId={} rule=UPSTREAM_UNEXPECTED error={}",
                    requestId, e.getClass().getSimpleName());
            throw reject(requestId, GatewayErrorCode.UPSTREAM_ERROR,
                    "UPSTREAM_UNEXPECTED", "上游调用发生未分类错误");
        }

        if (response == null) {
            throw reject(requestId, GatewayErrorCode.UPSTREAM_MALFORMED,
                    "UPSTREAM_EMPTY_RESPONSE", "上游返回空响应");
        }

        if (response.status() == 503) {
            log.warn("requestId={} rule=UPSTREAM_RETRY_ONCE basis=status=503", requestId);
            try {
                response = upstreamClient.invoke(order);
            } catch (UpstreamException e) {
                throw normalizeUpstreamException(requestId, e);
            }
        }

        if (response.status() < 200 || response.status() >= 300) {
            String basis = "upstreamStatus=" + response.status()
                    + ", mapped=UPSTREAM_ERROR（非 2xx 且不可重试）";
            throw reject(requestId, GatewayErrorCode.UPSTREAM_ERROR,
                    "UPSTREAM_ERROR_STATUS", basis);
        }

        return parseSuccessBody(requestId, order, response.rawBody());
    }

    private OrderResult parseSuccessBody(String requestId, InternalOrder order, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw reject(requestId, GatewayErrorCode.UPSTREAM_MALFORMED,
                    "UPSTREAM_MALFORMED_BODY", "上游 2xx 响应体为空");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw reject(requestId, GatewayErrorCode.UPSTREAM_MALFORMED,
                    "UPSTREAM_MALFORMED_BODY",
                    "上游 2xx 响应体不是合法 JSON（已归一化，原始内容不外泄）");
        }
        if (node == null || !node.isObject()
                || !node.hasNonNull("orderId")
                || !node.hasNonNull("accepted")) {
            throw reject(requestId, GatewayErrorCode.UPSTREAM_MALFORMED,
                    "UPSTREAM_MALFORMED_BODY",
                    "上游 JSON 缺少必填字段 orderId/accepted");
        }
        if (!node.get("accepted").asBoolean()) {
            throw reject(requestId, GatewayErrorCode.UPSTREAM_ERROR,
                    "UPSTREAM_NOT_ACCEPTED", "上游明确拒绝了订单");
        }

        OrderResult result = new OrderResult(
                node.get("orderId").asText(),
                order.getIdempotencyKey(),
                order.getProductName(),
                order.getQuantity(),
                order.totalAmountYuan(),
                order.getCurrency(),
                "ACCEPTED");
        log.info("requestId={} rule=UPSTREAM_NORMALIZED basis=status=200 orderId={}",
                requestId, result.orderId());
        return result;
    }

    private GatewayException normalizeUpstreamException(String requestId, UpstreamException e) {
        // 关键：只把“分类”翻译成对外错误码，原始异常消息/类型不进入对外响应
        return switch (e.getKind()) {
            case TIMEOUT -> reject(requestId, GatewayErrorCode.UPSTREAM_TIMEOUT,
                    "UPSTREAM_TIMEOUT", "上游在限定时间内未响应（已归一化为超时，可稍后重试）");
            case ERROR_STATUS -> reject(requestId, GatewayErrorCode.UPSTREAM_ERROR,
                    "UPSTREAM_ERROR_STATUS",
                    "upstreamStatus=" + e.getUpstreamStatus() + ", mapped=UPSTREAM_ERROR");
        };
    }

    private GatewayException reject(String requestId, GatewayErrorCode code, String rule, String basis) {
        log.warn("requestId={} rule={} basis={} mappedCode={}", requestId, rule, basis, code.name());
        return new GatewayException(code, rule, basis, requestId);
    }
}
