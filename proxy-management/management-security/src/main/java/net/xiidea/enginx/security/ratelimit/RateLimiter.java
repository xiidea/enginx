package net.xiidea.enginx.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token buckets, one per caller and tier.
 *
 * <p>State is per instance. Behind two replicas a caller therefore gets up to twice the configured
 * allowance, which is a deliberate trade: the alternative is a round trip to a shared store on
 * every single request, putting a network hop and a new dependency in front of the whole API to
 * make an approximate limit exact. The limit exists to stop abuse and runaway clients, and it does
 * that at 2x as well as at 1x. Where an exact global limit is required -- in front of an ACME
 * authority's quota, say -- it belongs at the ingress, and operations.md says so.
 *
 * <p>Buckets are evicted wholesale once the map exceeds its ceiling. Crude, but the property that
 * matters is that memory stays bounded: a caller rotating tokens must not be able to make the
 * limiter itself the thing that exhausts the heap.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    private final RateLimitProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong evictions = new AtomicLong();

    public RateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    /**
     * Takes one token for this caller in this tier.
     *
     * @return the outcome, carrying what a client needs in order to back off correctly
     */
    public Decision tryConsume(String caller, RateLimitTier tier) {
        if (!properties.enabled()) {
            return Decision.allowed(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        if (buckets.size() > properties.maxTrackedCallers()) {
            buckets.clear();
            evictions.incrementAndGet();
        }

        Bucket bucket = buckets.computeIfAbsent(caller + "|" + tier, key -> newBucket(tier));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        return probe.isConsumed()
                ? Decision.allowed(probe.getRemainingTokens(), capacity(tier))
                : Decision.denied(Duration.ofNanos(probe.getNanosToWaitForRefill()), capacity(tier));
    }

    /** How many times the bucket map has been cleared. Surfaced for tests and for tuning. */
    public long evictions() {
        return evictions.get();
    }

    private Bucket newBucket(RateLimitTier tier) {
        // Greedy refill: tokens trickle back continuously rather than all arriving at the end of
        // the period. An intervally refill lets a caller spend the whole allowance in the last
        // instant of one window and again in the first of the next -- twice the intended burst,
        // precisely at the boundary where a retry storm lands.
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity(tier)).refillGreedy(capacity(tier), period(tier)))
                .build();
    }

    private long capacity(RateLimitTier tier) {
        return switch (tier) {
            case READ -> properties.readCapacity();
            case WRITE -> properties.writeCapacity();
            case SENSITIVE -> properties.sensitiveCapacity();
        };
    }

    private Duration period(RateLimitTier tier) {
        return switch (tier) {
            case READ -> properties.readRefillPeriod();
            case WRITE -> properties.writeRefillPeriod();
            case SENSITIVE -> properties.sensitiveRefillPeriod();
        };
    }

    /**
     * @param allowed    whether the token was granted
     * @param remaining  tokens left in the bucket
     * @param retryAfter how long until a token is available
     * @param limit      the bucket's capacity, for the response headers
     */
    public record Decision(boolean allowed, long remaining, Duration retryAfter, long limit) {

        static Decision allowed(long remaining, long limit) {
            return new Decision(true, remaining, Duration.ZERO, limit);
        }

        static Decision denied(Duration retryAfter, long limit) {
            return new Decision(false, 0, retryAfter, limit);
        }

        /** Always at least one second: a {@code Retry-After: 0} invites an immediate retry. */
        public long retryAfterSeconds() {
            return Math.max(1, retryAfter.toSeconds());
        }
    }
}
