package net.xiidea.enginx.security.ratelimit;

/** Which bucket a request draws from. */
public enum RateLimitTier {
    READ,
    WRITE,
    /**
     * Operations whose cost or blast radius is out of proportion to the request: issuing a
     * certificate against an authority with weekly quotas, triggering a deployment or a rollback
     * on live hosts, and changing who can do any of it.
     */
    SENSITIVE
}
