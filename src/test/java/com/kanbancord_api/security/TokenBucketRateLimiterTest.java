package com.kanbancord_api.security;

import com.kanbancord_api.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock::get);
    private final RateLimitProperties.Limit limit = new RateLimitProperties.Limit(3, 60);

    @Test
    void allowsABurstUpToCapacity_thenReportsWhenToRetry() {
        for (int i = 0; i < 3; i++) {
            assertEquals(0, limiter.tryAcquire("a", limit));
        }
        assertEquals(1, limiter.tryAcquire("a", limit), "one token per second at 60/min");
    }

    @Test
    void refillsOverTime_butNeverAboveCapacity() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a", limit);
        }

        clock.addAndGet(SECOND);
        assertEquals(0, limiter.tryAcquire("a", limit));
        assertTrue(limiter.tryAcquire("a", limit) > 0);

        clock.addAndGet(3_600 * SECOND);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, limiter.tryAcquire("a", limit));
        }
        assertTrue(limiter.tryAcquire("a", limit) > 0);
    }

    @Test
    void keysHaveSeparateBuckets() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a", limit);
        }
        assertTrue(limiter.tryAcquire("a", limit) > 0);
        assertEquals(0, limiter.tryAcquire("b", limit));
    }
}
