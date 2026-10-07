package com.github.lisama569.versionedapigatewayjava.circuitbreaker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单依赖维度的熔断器。
 *
 * <p>状态机：
 * <ul>
 *   <li>CLOSED：正常放行；观察窗口内失败累计到 {@code failureThreshold} 后转入 OPEN；</li>
 *   <li>OPEN：拒绝放行（快速失败/降级），冷却 {@code cooldownMillis} 后转入 HALF_OPEN；</li>
 *   <li>HALF_OPEN：同时最多放行 {@code halfOpenPermits} 个试探请求；
 *       任一探成功 -> CLOSED（窗口清零），任一探失败 -> OPEN（重新冷却）。</li>
 * </ul>
 *
 * <p>并发安全：所有状态迁移与计数都在本实例的监视器锁内完成，
 * 半开放行数严格受限，不会因并发挤入而超发。时间源可注入，测试可确定性推进。
 */
public class CircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** 半开试探名额占满时给调用方的重试提示（毫秒），取冷却时长的一个确定小份额。 */
    static final long HALF_OPEN_RETRY_HINT_CAP_MILLIS = 500;

    private final String dependency;
    private final CircuitBreakerConfig config;
    private final LongSupplier nowMillis;

    private CircuitState state = CircuitState.CLOSED;
    private final Deque<Long> failureTimestamps = new ArrayDeque<>();
    private long openedAtMillis = -1;
    private int halfOpenInFlight = 0;
    private long lastActivityMillis;

    public CircuitBreaker(String dependency, CircuitBreakerConfig config, LongSupplier nowMillis) {
        this.dependency = dependency;
        this.config = config;
        this.nowMillis = nowMillis;
        this.lastActivityMillis = nowMillis.getAsLong();
    }

    public String dependency() {
        return dependency;
    }

    public synchronized CircuitState state() {
        touch();
        maybeTransitionToHalfOpen(nowMillis.getAsLong());
        return state;
    }

    /**
     * 请求调用上游前申请放行。OPEN 拒绝并给出冷却剩余时间；
     * 冷却结束自动进入 HALF_OPEN 并限量放行试探；CLOSED 直接放行。
     */
    public synchronized PermitDecision tryAcquirePermit() {
        touch();
        long now = nowMillis.getAsLong();
        maybeTransitionToHalfOpen(now);
        switch (state) {
            case OPEN -> {
                long retryAfter = Math.max(1, openedAtMillis + config.cooldownMillis() - now);
                log.info("circuit[{}] rule=reject-open basis='cooling down, retryAfterMs={}'", dependency, retryAfter);
                return new PermitDecision(false, CircuitState.OPEN, retryAfter,
                        "circuit OPEN, cooldown remaining " + retryAfter + "ms");
            }
            case HALF_OPEN -> {
                if (halfOpenInFlight >= config.halfOpenPermits()) {
                    long hint = Math.min(config.cooldownMillis(), HALF_OPEN_RETRY_HINT_CAP_MILLIS);
                    log.info("circuit[{}] rule=reject-half-open-saturated basis='probe slots {}/{} in use'",
                            dependency, halfOpenInFlight, config.halfOpenPermits());
                    return new PermitDecision(false, CircuitState.HALF_OPEN, hint,
                            "half-open probe slots exhausted (" + halfOpenInFlight + "/"
                                    + config.halfOpenPermits() + ")");
                }
                halfOpenInFlight++;
                log.info("circuit[{}] rule=permit-half-open-probe basis='probe slot acquired {}/{}'",
                        dependency, halfOpenInFlight, config.halfOpenPermits());
                return new PermitDecision(true, CircuitState.HALF_OPEN, 0,
                        "half-open probe permitted (" + halfOpenInFlight + "/" + config.halfOpenPermits() + ")");
            }
            default -> {
                return new PermitDecision(true, CircuitState.CLOSED, 0, "circuit CLOSED, pass through");
            }
        }
    }

    /** 上游调用成功：CLOSED 无操作；HALF_OPEN 试探成功则恢复关闭并清空失败窗口。 */
    public synchronized void onSuccess() {
        touch();
        if (state == CircuitState.HALF_OPEN) {
            halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
            transitionTo(CircuitState.CLOSED);
            failureTimestamps.clear();
            openedAtMillis = -1;
            log.info("circuit[{}] rule=half-open-probe-succeeded basis='probe OK -> CLOSED, failure window cleared'",
                    dependency);
        }
        // CLOSED 下成功不改变窗口：历史失败随窗口滑动自然过期
    }

    /** 上游调用失败（超时/错误状态/畸形均计入）：CLOSED 累计窗口，HALF_OPEN 立即回到 OPEN。 */
    public synchronized void onFailure() {
        touch();
        long now = nowMillis.getAsLong();
        if (state == CircuitState.HALF_OPEN) {
            halfOpenInFlight = Math.max(0, halfOpenInFlight - 1);
            open(now, "half-open probe failed -> re-open, cooldown restarts");
            return;
        }
        if (state == CircuitState.OPEN) {
            return; // OPEN 期间无真实放行，不重复计数
        }
        evictExpired(now);
        failureTimestamps.addLast(now);
        log.info("circuit[{}] rule=failure-recorded basis='failures in window={}/{}'",
                dependency, failureTimestamps.size(), config.failureThreshold());
        if (failureTimestamps.size() >= config.failureThreshold()) {
            open(now, "failure threshold reached: " + failureTimestamps.size() + "/"
                    + config.failureThreshold() + " within " + config.windowMillis() + "ms");
        }
    }

    /** 最近一次活动时间，用于注册表回收空闲状态。 */
    public synchronized long lastActivityMillis() {
        return lastActivityMillis;
    }

    /** 观察窗口内当前累计失败数（测试与日志用）。 */
    public synchronized int failuresInWindow() {
        long now = nowMillis.getAsLong();
        evictExpired(now);
        return failureTimestamps.size();
    }

    private void maybeTransitionToHalfOpen(long now) {
        if (state == CircuitState.OPEN && now - openedAtMillis >= config.cooldownMillis()) {
            transitionTo(CircuitState.HALF_OPEN);
            halfOpenInFlight = 0;
            log.info("circuit[{}] rule=cooldown-elapsed basis='cooldown {}ms elapsed -> HALF_OPEN, permits={}'",
                    dependency, config.cooldownMillis(), config.halfOpenPermits());
        }
    }

    private void open(long now, String basis) {
        transitionTo(CircuitState.OPEN);
        openedAtMillis = now;
        failureTimestamps.clear(); // 重新冷却期间从零开始观察
        halfOpenInFlight = 0;
        log.warn("circuit[{}] rule=circuit-opened basis='{}'", dependency, basis);
    }

    private void transitionTo(CircuitState next) {
        log.info("circuit[{}] state-transition {} -> {}", dependency, state, next);
        state = next;
    }

    private void evictExpired(long now) {
        while (!failureTimestamps.isEmpty()
                && now - failureTimestamps.peekFirst() >= config.windowMillis()) {
            failureTimestamps.pollFirst();
        }
    }

    private void touch() {
        lastActivityMillis = nowMillis.getAsLong();
    }
}
