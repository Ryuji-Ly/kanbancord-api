package com.kanbancord_api.security;

import com.kanbancord_api.config.RateLimitProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * In-memory token buckets keyed by caller. Single-instance only, which matches the deployment.
 */
public class TokenBucketRateLimiter {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final int SWEEP_EVERY = 1_024;

    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger acquisitions = new AtomicInteger();

    public TokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    TokenBucketRateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Takes one token from the caller's bucket.
     *
     * @return 0 when allowed, otherwise the number of seconds until a token is available
     */
    public long tryAcquire(String key, RateLimitProperties.Limit limit) {
        long now = nanoClock.getAsLong();
        if (acquisitions.incrementAndGet() % SWEEP_EVERY == 0) {
            buckets.values().removeIf(bucket -> bucket.isFullAt(now));
        }

        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(limit, now));
        return bucket.tryTake(now);
    }

    private static final class Bucket {

        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long updatedAt;

        private Bucket(RateLimitProperties.Limit limit, long now) {
            this.capacity = limit.getCapacity();
            this.tokensPerNano = (double) limit.getPerMinute() / NANOS_PER_MINUTE;
            this.tokens = capacity;
            this.updatedAt = now;
        }

        private synchronized long tryTake(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            if (tokensPerNano <= 0) {
                return 60;
            }
            double nanosUntilToken = (1 - tokens) / tokensPerNano;
            return Math.max(1, (long) Math.ceil(nanosUntilToken / 1_000_000_000d));
        }

        /** A full bucket behaves exactly like a new one, so it can be dropped. */
        private synchronized boolean isFullAt(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            long elapsed = now - updatedAt;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
                updatedAt = now;
            }
        }
    }
}
