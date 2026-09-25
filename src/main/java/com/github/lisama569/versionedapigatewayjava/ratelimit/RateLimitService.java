package com.github.lisama569.versionedapigatewayjava.ratelimit;

import com.github.lisama569.versionedapigatewayjava.common.error.GatewayErrorCode;
import com.github.lisama569.versionedapigatewayjava.common.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按调用方维度的三重控制，所有判定在“每调用方一把锁”内原子完成，确保并发不超发：
 * <ol>
 *   <li>固定窗口配额 quotaPerWindow：窗口内累计接纳请求数，耗尽即 {@code QUOTA_EXHAUSTED}；
 *       窗口按时钟确定性轮换（测试用 MutableClock 即可精确控制）。</li>
 *   <li>令牌桶速率：容量 bucketCapacity，按 refillTokensPerSecond 持续补充（小数令牌），
 *       无令牌即 {@code RATE_LIMITED_TRANSIENT}（瞬时，稍后可恢复）。</li>
 *   <li>并发槽 maxConcurrent：在途请求数上限，满了也是瞬时限流。</li>
 * </ol>
 * 判定顺序固定为 配额 -> 令牌 -> 并发，保证可解释、可测试；被拒请求不扣任何额度。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    public static final String ANONYMOUS_CALLER = "anonymous";

    private final Clock clock;
    private final RateLimitConfig config;
    private final Map<String, CallerState> states = new ConcurrentHashMap<>();

    public RateLimitService(Clock clock, RateLimitConfig config) {
        this.clock = clock;
        this.config = config;
    }

    public RateLimitConfig config() {
        return config;
    }

    /**
     * 尝试为调用方获取一个处理名额。成功返回 {@link Permit}（必须 close 以释放并发槽）；
     * 失败抛 {@link GatewayException}，错误码区分瞬时（429 transient）与配额耗尽（429 quota）。
     */
    public Permit tryAcquire(String callerId) {
        String caller = (callerId == null || callerId.isBlank()) ? ANONYMOUS_CALLER : callerId.trim();
        CallerState st = states.computeIfAbsent(caller, c -> new CallerState(c, clock.nowMillis(), config));
        synchronized (st) {
            long now = clock.nowMillis();
            st.rollWindowIfNeeded(now);
            st.refillTokens(now);

            // 1) 配额：持久预算，窗口内耗尽即明确拒绝
            if (st.quotaUsed >= config.quotaPerWindow()) {
                String basis = String.format(
                        "caller=%s rule=quota windowStart=%d quotaUsed=%d/%d",
                        caller, st.windowStart, st.quotaUsed, config.quotaPerWindow());
                log.warn("rate-limit decision=REJECT reason=QUOTA_EXHAUSTED {}", basis);
                throw new GatewayException(GatewayErrorCode.QUOTA_EXHAUSTED, "QUOTA_EXHAUSTED", basis);
            }

            // 2) 令牌桶：瞬时速率
            if (st.tokens < 1.0d) {
                String basis = String.format(
                        "caller=%s rule=token-bucket availableTokens=%.3f capacity=%d refillRate=%.3f/s",
                        caller, st.tokens, config.bucketCapacity(), config.refillTokensPerSecond());
                log.warn("rate-limit decision=REJECT reason=RATE_LIMITED_TRANSIENT {}", basis);
                throw new GatewayException(GatewayErrorCode.RATE_LIMITED_TRANSIENT,
                        "TOKEN_BUCKET_EMPTY", basis);
            }

            // 3) 并发槽
            if (st.inFlight >= config.maxConcurrent()) {
                String basis = String.format(
                        "caller=%s rule=concurrency inFlight=%d/%d",
                        caller, st.inFlight, config.maxConcurrent());
                log.warn("rate-limit decision=REJECT reason=RATE_LIMITED_TRANSIENT {}", basis);
                throw new GatewayException(GatewayErrorCode.RATE_LIMITED_TRANSIENT,
                        "CONCURRENCY_LIMIT", basis);
            }

            // 接纳：一次性原子扣减（拒绝路径不会到达这里，因此不会误扣）
            st.quotaUsed++;
            st.tokens -= 1.0d;
            st.inFlight++;
            String acceptedBasis = String.format(
                    "caller=%s quotaUsed=%d/%d tokensLeft=%.3f inFlight=%d/%d",
                    caller, st.quotaUsed, config.quotaPerWindow(),
                    st.tokens, st.inFlight, config.maxConcurrent());
            log.info("rate-limit decision=ACCEPT {}", acceptedBasis);
            return new Permit(caller, acceptedBasis, () -> release(caller));
        }
    }

    private void release(String caller) {
        CallerState st = states.get(caller);
        if (st == null) {
            return;
        }
        synchronized (st) {
            if (st.inFlight > 0) {
                st.inFlight--;
            }
            log.info("rate-limit decision=RELEASE caller={} inFlight={}/{}",
                    caller, st.inFlight, config.maxConcurrent());
        }
    }

    /** 仅供测试/监控：读取某调用方当前快照（在锁内构造，避免竞态）。 */
    public Map<String, Object> snapshot(String callerId) {
        String caller = (callerId == null || callerId.isBlank()) ? ANONYMOUS_CALLER : callerId.trim();
        CallerState st = states.get(caller);
        if (st == null) {
            return Map.of();
        }
        synchronized (st) {
            st.rollWindowIfNeeded(clock.nowMillis());
            st.refillTokens(clock.nowMillis());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caller", caller);
            m.put("quotaUsed", st.quotaUsed);
            m.put("tokens", st.tokens);
            m.put("inFlight", st.inFlight);
            m.put("windowStart", st.windowStart);
            return m;
        }
    }

    /** 每调用方可变状态；任何读写都必须在 synchronized(this) 内。 */
    private static final class CallerState {
        private final String caller;
        private final RateLimitConfig config;
        private long windowStart;
        private int quotaUsed;
        private double tokens;
        private int inFlight;
        private long lastRefillMillis;

        CallerState(String caller, long now, RateLimitConfig config) {
            this.caller = caller;
            this.config = config;
            this.windowStart = now;
            this.quotaUsed = 0;
            this.tokens = config.bucketCapacity();
            this.inFlight = 0;
            this.lastRefillMillis = now;
        }

        /** 固定窗口轮换：到达/越过下一个窗口起点时，配额计数归零。 */
        void rollWindowIfNeeded(long now) {
            while (windowStart + config.windowMillis() <= now) {
                windowStart += config.windowMillis();
                quotaUsed = 0;
            }
        }

        /** 令牌确定性补充：按流逝毫秒数线性恢复，上限为桶容量（不累积）。 */
        void refillTokens(long now) {
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                double gained = (elapsed / 1000.0d) * config.refillTokensPerSecond();
                tokens = Math.min(config.bucketCapacity(), tokens + gained);
                lastRefillMillis = now;
            }
        }
    }
}
