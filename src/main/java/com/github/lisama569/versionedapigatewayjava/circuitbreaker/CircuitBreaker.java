package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * 单维度熔断器状态机，所有状态迁移在监视器锁内完成，并发安全：
 *
 * <pre>
 * CLOSED --(窗口内失败数 >= failureThreshold)--> OPEN
 * OPEN   --(冷却 cooldownMillis 期满)----------> HALF_OPEN
 * HALF_OPEN --(任一试探成功)--> CLOSED（清空失败统计）
 * HALF_OPEN --(任一试探失败)--> OPEN（重新计时冷却）
 * </pre>
 *
 * 试探阶段用 halfOpenInFlight 计数严格限制同时放行数（<= halfOpenPermits），
 * 许可必须由 onSuccess/onFailure 归还，并发下不会超发。
 * 时间源可注入，测试可确定性推进。
 */
public class CircuitBreaker {
    private final String dependency;
    private final CircuitBreakerConfig config;
    private final LongSupplier nowMillis;

    private CircuitState state = CircuitState.CLOSED;
    private final Deque<Long> failureTimestamps = new ArrayDeque<>();
    private long openedAtMillis = -1;
    private int halfOpenInFlight = 0;
    private long lastTouchedMillis;

    public CircuitBreaker(String dependency, CircuitBreakerConfig config, LongSupplier nowMillis) {
        this.dependency = dependency;
        this.config = config;
        this.nowMillis = nowMillis;
        this.lastTouchedMillis = nowMillis.getAsLong();
    }

    public String dependency() {
        return dependency;
    }

    /** 尝试获取一次上游调用许可；获得许可的调用必须以 onSuccess/onFailure 上报结果。 */
    public synchronized CircuitBreakerDecision tryAcquire() {
        long now = nowMillis.getAsLong();
        lastTouchedMillis = now;
        switch (state) {
            case CLOSED -> {
                return new CircuitBreakerDecision(true, CircuitState.CLOSED, 0,
                        "circuit closed for '" + dependency + "', call permitted");
            }
            case OPEN -> {
                long elapsed = now - openedAtMillis;
                if (elapsed >= config.cooldownMillis()) {
                    transitionToHalfOpen(now);
                    return acquireHalfOpenPermit(now);
                }
                long retryAfter = config.cooldownMillis() - elapsed;
                return new CircuitBreakerDecision(false, CircuitState.OPEN, retryAfter,
                        "circuit open for '" + dependency + "', cooldown remaining " + retryAfter + "ms");
            }
            case HALF_OPEN -> {
                return acquireHalfOpenPermit(now);
            }
            default -> throw new IllegalStateException("unknown state " + state);
        }
    }

    private CircuitBreakerDecision acquireHalfOpenPermit(long now) {
        if (halfOpenInFlight < config.halfOpenPermits()) {
            halfOpenInFlight++;
            return new CircuitBreakerDecision(true, CircuitState.HALF_OPEN, 0,
                    "circuit half-open for '" + dependency + "', probe permit granted ("
                            + halfOpenInFlight + "/" + config.halfOpenPermits() + ")");
        }
        return new CircuitBreakerDecision(false, CircuitState.HALF_OPEN, config.cooldownMillis(),
                "circuit half-open for '" + dependency + "', probe permits exhausted ("
                        + halfOpenInFlight + "/" + config.halfOpenPermits() + "), fast-fail");
    }

    /** 上报一次上游调用成功。 */
    public synchronized void onSuccess() {
        long now = nowMillis.getAsLong();
        lastTouchedMillis = now;
        if (state == CircuitState.HALF_OPEN) {
            halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
            transitionToClosed(now);
        }
        // CLOSED 下成功不改变失败统计（失败按滑动窗口自然过期）
    }

    /** 上报一次上游调用失败（超时/错误状态/畸形响应）。 */
    public synchronized void onFailure() {
        long now = nowMillis.getAsLong();
        lastTouchedMillis = now;
        switch (state) {
            case CLOSED -> {
                failureTimestamps.addLast(now);
                pruneWindow(now);
                if (failureTimestamps.size() >= config.failureThreshold()) {
                    transitionToOpen(now);
                }
            }
            case HALF_OPEN -> {
                halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
                transitionToOpen(now); // 试探失败：重新熔断，冷却重新计时
            }
            case OPEN -> {
                // 打开状态下不应有在途调用；防御性忽略
            }
        }
    }

    private void transitionToOpen(long now) {
        state = CircuitState.OPEN;
        openedAtMillis = now;
        failureTimestamps.clear();
    }

    private void transitionToHalfOpen(long now) {
        state = CircuitState.HALF_OPEN;
        halfOpenInFlight = 0;
    }

    private void transitionToClosed(long now) {
        state = CircuitState.CLOSED;
        failureTimestamps.clear();
        openedAtMillis = -1;
    }

    private void pruneWindow(long now) {
        long cutoff = now - config.windowMillis();
        while (!failureTimestamps.isEmpty() && failureTimestamps.peekFirst() <= cutoff) {
            failureTimestamps.pollFirst();
        }
    }

    public synchronized CircuitState state() {
        // 惰性推进 OPEN -> HALF_OPEN 的展示状态（不改变许可语义，tryAcquire 才做真正迁移）
        if (state == CircuitState.OPEN
                && nowMillis.getAsLong() - openedAtMillis >= config.cooldownMillis()) {
            return CircuitState.HALF_OPEN;
        }
        return state;
    }

    /** 窗口内当前失败计数（测试与日志用）。 */
    public synchronized int failuresInWindow() {
        long now = nowMillis.getAsLong();
        pruneWindow(now);
        return failureTimestamps.size();
    }

    /** 当前试探在途许可数（测试用）。 */
    public synchronized int halfOpenInFlight() {
        return halfOpenInFlight;
    }

    /** 最近一次活动时间（用于空闲回收）。 */
    public synchronized long lastTouchedMillis() {
        return lastTouchedMillis;
    }
}
