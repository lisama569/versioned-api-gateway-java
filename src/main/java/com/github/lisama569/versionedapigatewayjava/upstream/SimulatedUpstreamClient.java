package com.github.lisama569.versionedapigatewayjava.upstream;

import com.github.lisama569.versionedapigatewayjava.model.InternalOrder;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地上游模拟器：
 * - 脚本队列注入每次调用行为（OK/TIMEOUT/ERROR_STATUS/MALFORMED），脚本耗尽后默认 OK；
 * - 按幂等键去重：同一键只计一次“副作用”（effectCount），重复提交直接回放既有凭证；
 * - TIMEOUT_AFTER_SIDE_EFFECT 模拟“已生效但响应挂起”，用于验证重试不重复副作用。
 */
public class SimulatedUpstreamClient implements UpstreamClient {
    private final AtomicInteger invocationCount = new AtomicInteger();
    private final AtomicInteger effectCount = new AtomicInteger();
    private final Queue<UpstreamKind> script = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, UpstreamReceipt> processed = new ConcurrentHashMap<>();
    private volatile long simulatedLatencyMillis = 0;
    private volatile int errorStatus = 500;

    public void enqueue(UpstreamKind kind) {
        script.add(kind);
    }

    public void setSimulatedLatencyMillis(long millis) {
        this.simulatedLatencyMillis = millis;
    }

    public void setErrorStatus(int status) {
        this.errorStatus = status;
    }

    @Override
    public UpstreamReceipt submit(String idempotencyKey, InternalOrder order) {
        int seq = invocationCount.incrementAndGet();
        UpstreamKind kind = script.poll();
        if (kind == null) {
            kind = UpstreamKind.OK;
        }
        switch (kind) {
            case TIMEOUT -> {
                hang(); // 未生效即挂起，结果未知
            }
            case TIMEOUT_AFTER_SIDE_EFFECT -> {
                // 已生效后响应挂起：记录副作用与凭证，再挂起；重试时命中回放
                UpstreamReceipt receipt = new UpstreamReceipt("UP-" + seq, "ACCEPTED");
                if (!hasKey(idempotencyKey) || processed.putIfAbsent(idempotencyKey, receipt) == null) {
                    effectCount.incrementAndGet();
                }
                hang();
            }
            case ERROR_STATUS -> throw new UpstreamFailureException(errorStatus,
                    "upstream responded with status " + errorStatus);
            case MALFORMED -> throw new UpstreamMalformedException(
                    "upstream returned unparsable payload: <not-json>");
            case OK -> {
                if (simulatedLatencyMillis > 0) {
                    try {
                        Thread.sleep(simulatedLatencyMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted", e);
                    }
                }
                UpstreamReceipt receipt = new UpstreamReceipt("UP-" + seq, "ACCEPTED");
                if (!hasKey(idempotencyKey)) {
                    // 无幂等键：一次性请求，不做去重
                    effectCount.incrementAndGet();
                    return receipt;
                }
                UpstreamReceipt existing = processed.putIfAbsent(idempotencyKey, receipt);
                if (existing == null) {
                    effectCount.incrementAndGet();
                    return receipt;
                }
                return existing; // 同键重放，不产生新副作用
            }
            default -> throw new IllegalStateException("unknown kind " + kind);
        }
        throw new IllegalStateException("unreachable");
    }

    private static boolean hasKey(String idempotencyKey) {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    private static void hang() {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while upstream hung", e);
        }
    }

    public int invocationCount() {
        return invocationCount.get();
    }

    /** 真实生效次数（同幂等键只计一次）。 */
    public int effectCount() {
        return effectCount.get();
    }

    public void reset() {
        script.clear();
        processed.clear();
        invocationCount.set(0);
        effectCount.set(0);
        simulatedLatencyMillis = 0;
        errorStatus = 500;
    }
}
