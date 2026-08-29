package net.xiidea.enginx.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Rate limits, per caller.
 *
 * <p>Two tiers rather than one global number, because the two kinds of request fail differently.
 * A flood of reads costs CPU and can be absorbed; a flood of writes creates deployments, burns an
 * ACME authority's per-week issuance quota, and reloads NGINX on real hosts — damage that outlives
 * the request and cannot be undone by shedding load later.
 *
 * @param enabled            whether limiting is applied at all
 * @param readCapacity       burst size for safe methods
 * @param readRefillPeriod   how long a full read bucket takes to refill
 * @param writeCapacity      burst size for state-changing methods
 * @param writeRefillPeriod  how long a full write bucket takes to refill
 * @param sensitiveCapacity  burst size for the operations named in {@link SensitivePaths}
 * @param sensitiveRefillPeriod how long a full sensitive bucket takes to refill
 * @param maxTrackedCallers  ceiling on distinct buckets held, so an attacker rotating identities
 *                           cannot turn the limiter itself into the memory leak that takes the
 *                           service down
 */
@ConfigurationProperties(prefix = "enginx.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        long readCapacity,
        Duration readRefillPeriod,
        long writeCapacity,
        Duration writeRefillPeriod,
        long sensitiveCapacity,
        Duration sensitiveRefillPeriod,
        int maxTrackedCallers) {

    public RateLimitProperties {
        readCapacity = readCapacity <= 0 ? 300 : readCapacity;
        readRefillPeriod = readRefillPeriod == null ? Duration.ofMinutes(1) : readRefillPeriod;
        writeCapacity = writeCapacity <= 0 ? 60 : writeCapacity;
        writeRefillPeriod = writeRefillPeriod == null ? Duration.ofMinutes(1) : writeRefillPeriod;
        sensitiveCapacity = sensitiveCapacity <= 0 ? 10 : sensitiveCapacity;
        sensitiveRefillPeriod = sensitiveRefillPeriod == null ? Duration.ofMinutes(1) : sensitiveRefillPeriod;
        maxTrackedCallers = maxTrackedCallers <= 0 ? 10_000 : maxTrackedCallers;
    }
}
