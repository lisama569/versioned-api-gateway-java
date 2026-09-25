package com.github.lisama569.versionedapigatewayjava.upstream;

import com.github.lisama569.versionedapigatewayjava.common.model.InternalOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地模拟上游依赖。<b>不发起任何真实网络请求、不需要外部凭据</b>。
 *
 * <p>通过商品名前缀（脚本化）触发不同上游场景，便于测试错误归一化：</p>
 * <ul>
 *   <li>{@code TIMEOUT:...}  -> 抛 {@link UpstreamException}(TIMEOUT)，模拟读超时</li>
 *   <li>{@code STATUS_503:...} -> 返回 503（瞬时错误，幂等请求可安全重试一次）</li>
 *   <li>{@code STATUS_500:...} -> 返回 500（不可重试）</li>
 *   <li>{@code MALFORMED:...}  -> 200 但响应体不是合法 JSON</li>
 *   <li>{@code OK_503:...}     -> 同一幂等键首次 503、第二次成功（验证重试不产生重复副作用）</li>
 *   <li>其他 -> 200，返回合法 JSON</li>
 * </ul>
 * 同时统计总调用次数与按幂等键的调用次数，用于断言“上游副作用只发生一次”。
 */
public class MockUpstreamClient implements UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(MockUpstreamClient.class);

    static final String PFX_TIMEOUT = "TIMEOUT:";
    static final String PFX_503 = "STATUS_503:";
    static final String PFX_500 = "STATUS_500:";
    static final String PFX_MALFORMED = "MALFORMED:";
    static final String PFX_OK_503 = "OK_503:";

    private final AtomicInteger totalInvocations = new AtomicInteger();
    private final Map<String, AtomicInteger> invocationsByKey = new ConcurrentHashMap<>();

    @Override
    public UpstreamResponse invoke(InternalOrder order) {
        String key = order.getIdempotencyKey() == null ? "<no-key>" : order.getIdempotencyKey();
        int n = totalInvocations.incrementAndGet();
        invocationsByKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        String product = order.getProductName();
        log.info("upstream invoke#{} key={} product={} cents={} qty={} currency={}",
                n, key, product, order.getUnitPriceCents(), order.getQuantity(), order.getCurrency());

        if (product.startsWith(PFX_TIMEOUT)) {
            // 不做真实 sleep：测试必须快且确定，超时语义用异常类型表达
            throw new UpstreamException(UpstreamFailureKind.TIMEOUT, null,
                    "模拟上游读超时（product=" + product + "）");
        }
        if (product.startsWith(PFX_503)) {
            return new UpstreamResponse(503, "{\"error\":\"service unavailable\"}");
        }
        if (product.startsWith(PFX_500)) {
            return new UpstreamResponse(500, "{\"error\":\"internal server error\"}");
        }
        if (product.startsWith(PFX_MALFORMED)) {
            return new UpstreamResponse(200, "{this-is-not-json,,,");
        }
        if (product.startsWith(PFX_OK_503)) {
            int seen = invocationsByKey.get(key).get();
            if (seen == 1) {
                return new UpstreamResponse(503, "{\"error\":\"transient\"}");
            }
            return successBody(order, key);
        }
        return successBody(order, key);
    }

    private UpstreamResponse successBody(InternalOrder order, String key) {
        String body = String.format(
                "{\"orderId\":\"ORD-%s\",\"idempotencyKey\":\"%s\",\"accepted\":true,\"totalCents\":%d}",
                Integer.toHexString(System.identityHashCode(order)), key, order.totalAmountCents());
        return new UpstreamResponse(200, body);
    }

    public int invocationCount() {
        return totalInvocations.get();
    }

    public int invocationCount(String idempotencyKey) {
        AtomicInteger c = invocationsByKey.get(idempotencyKey);
        return c == null ? 0 : c.get();
    }
}
