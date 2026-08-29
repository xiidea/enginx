package net.xiidea.enginx.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static RateLimitProperties properties(boolean enabled) {
        return new RateLimitProperties(enabled, 5, Duration.ofMinutes(1), 3, Duration.ofMinutes(1),
                2, Duration.ofMinutes(1), 4);
    }

    @Test
    @DisplayName("allows up to the tier's capacity, then refuses")
    void exhaustsBucket() {
        RateLimiter limiter = new RateLimiter(properties(true));

        for (int i = 0; i < 2; i++) {
            assertThat(limiter.tryConsume("alice", RateLimitTier.SENSITIVE).allowed()).isTrue();
        }

        RateLimiter.Decision denied = limiter.tryConsume("alice", RateLimitTier.SENSITIVE);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.limit()).isEqualTo(2);
        // Never zero: a Retry-After of 0 tells a client to come straight back.
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("tiers are independent, so exhausting writes does not block reads")
    void tiersAreSeparate() {
        RateLimiter limiter = new RateLimiter(properties(true));

        for (int i = 0; i < 2; i++) {
            limiter.tryConsume("alice", RateLimitTier.SENSITIVE);
        }
        assertThat(limiter.tryConsume("alice", RateLimitTier.SENSITIVE).allowed()).isFalse();
        assertThat(limiter.tryConsume("alice", RateLimitTier.READ).allowed()).isTrue();
    }

    @Test
    @DisplayName("one caller's flood does not spend another caller's allowance")
    void callersAreSeparate() {
        RateLimiter limiter = new RateLimiter(properties(true));

        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("alice", RateLimitTier.SENSITIVE);
        }
        assertThat(limiter.tryConsume("alice", RateLimitTier.SENSITIVE).allowed()).isFalse();
        assertThat(limiter.tryConsume("bob", RateLimitTier.SENSITIVE).allowed()).isTrue();
    }

    @Test
    @DisplayName("bucket storage stays bounded when an attacker rotates identities")
    void evictsWhenTrackingTooManyCallers() {
        RateLimiter limiter = new RateLimiter(properties(true));

        for (int i = 0; i < 50; i++) {
            limiter.tryConsume("caller-" + i, RateLimitTier.READ);
        }

        // The point is not the exact count but that the map was reclaimed rather than growing
        // with every distinct key a caller cares to invent.
        assertThat(limiter.evictions()).isGreaterThan(0);
    }

    @Test
    @DisplayName("disabled means no limit at all, not a very large one")
    void disabledAllowsEverything() {
        RateLimiter limiter = new RateLimiter(properties(false));

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryConsume("alice", RateLimitTier.SENSITIVE).allowed()).isTrue();
        }
    }
}
