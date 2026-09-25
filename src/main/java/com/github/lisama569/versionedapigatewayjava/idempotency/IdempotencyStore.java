package com.github.lisama569.versionedapigatewayjava.idempotency;

import com.github.lisama569.versionedapigatewayjava.upstream.UpstreamReceipt;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 幂等记录：同一 idempotencyKey 只保留首次成功结果，重试/重放不产生重复副作用。 */
public class IdempotencyStore {
    private final ConcurrentMap<String, UpstreamReceipt> store = new ConcurrentHashMap<>();

    public Optional<UpstreamReceipt> get(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(key));
    }

    /** 首次放置返回 true；已存在（含并发竞争失败）返回 false。 */
    public boolean putIfAbsent(String key, UpstreamReceipt receipt) {
        if (key == null || key.isBlank()) {
            return true; // 无幂等键时不做去重，视为一次性请求
        }
        return store.putIfAbsent(key, receipt) == null;
    }

    public void clear() {
        store.clear();
    }
}
