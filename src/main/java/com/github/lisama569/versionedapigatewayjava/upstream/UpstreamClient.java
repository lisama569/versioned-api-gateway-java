package com.github.lisama569.versionedapigatewayjava.upstream;

import com.github.lisama569.versionedapigatewayjava.model.InternalOrder;

/**
 * 上游依赖抽象（本地模拟实现，无真实网络）。
 * 必须携带幂等键：上游对同一键只生效一次，超时后的重试不会重复产生副作用。
 */
public interface UpstreamClient {
    UpstreamReceipt submit(String idempotencyKey, InternalOrder order);
}
