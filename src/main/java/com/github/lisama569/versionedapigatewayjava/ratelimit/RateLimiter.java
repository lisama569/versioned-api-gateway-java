package com.github.lisama569.versionedapigatewayjava.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按调用方维度的限流器：
 * - 令牌桶控制瞬时速率（容量 burstCapacity，按 refillPerSecond 随时间确定恢复）；
 * - 固定窗口配额控制总量（quotaPerWindow / windowMillis）。
 * 时间源可注入，测试中可确定性推进；并发下通过按调用方串行化保证不超发。
 */
public class RateLimiter {
    private final LongSupplier nowMillis;
    private final CallerPolicy defaultPolicy;
    private final Map<String, CallerPolicy> callerPolicies = new ConcurrentHashMap<>();
    private final Map<String, CallerState> states = new ConcurrentHashMap<>();

    public RateLimiter(CallerPolicy defaultPolicy) {
        this(defaultPolicy, System::currentTimeMillis);
    }

    public RateLimiter(CallerPolicy defaultPolicy, LongSupplier nowMillis) {
        this.defaultPolicy = defaultPolicy;
        this.nowMillis = nowMillis;
    }

    public void setPolicy(String callerId, CallerPolicy policy) {
        callerPolicies.put(callerId, policy);
        states.remove(callerId);
    }

    public LimitDecision tryAcquire(String callerId) {
        CallerPolicy policy = callerPolicies.getOrDefault(callerId, defaultPolicy);
        CallerState state = states.computeIfAbsent(callerId, k -> CallerState.fresh(policy, nowMillis.getAsLong()));
        synchronized (state) {
            long now = nowMillis.getAsLong();
            state.refill(policy, now);
            if (now - state.windowStartMillis >= policy.windowMillis()) {
                state.windowStartMillis = now;
                state.usedInWindow = 0;
            }
            String basis = basis(policy, state, now);
            if (state.usedInWindow >= policy.quotaPerWindow()) {
                long retryAfter = Math.max(0, state.windowStartMillis + policy.windowMillis() - now);
                return new LimitDecision(false, true, retryAfter,
                        "quota exhausted: " + basis);
            }
            if (state.tokens < 1.0) {
                long retryAfter = policy.refillPerSecond() <= 0 ? Long.MAX_VALUE
                        : (long) Math.ceil((1.0 - state.tokens) / policy.refillPerSecond() * 1000.0);
                return new LimitDecision(false, false, retryAfter,
                        "transient rate limit: " + basis);
            }
            state.tokens -= 1.0;
            state.usedInWindow += 1;
            return new LimitDecision(true, false, 0, "accepted: " + basis);
        }
    }

    private static String basis(CallerPolicy policy, CallerState state, long now) {
        return "caller tokens=" + String.format("%.2f", state.tokens) + "/" + policy.burstCapacity()
                + ", quotaUsed=" + state.usedInWindow + "/" + policy.quotaPerWindow()
                + ", windowElapsedMs=" + (now - state.windowStartMillis);
    }

    /** 测试辅助：重置某调用方状态。 */
    public void reset(String callerId) {
        states.remove(callerId);
    }

    private static final class CallerState {
        double tokens;
        long lastRefillMillis;
        long windowStartMillis;
        long usedInWindow;

        static CallerState fresh(CallerPolicy policy, long now) {
            CallerState s = new CallerState();
            s.tokens = policy.burstCapacity();
            s.lastRefillMillis = now;
            s.windowStartMillis = now;
            s.usedInWindow = 0;
            return s;
        }

        void refill(CallerPolicy policy, long now) {
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                tokens = Math.min(policy.burstCapacity(),
                        tokens + elapsed * policy.refillPerSecond() / 1000.0);
                lastRefillMillis = now;
            }
        }
    }
}
