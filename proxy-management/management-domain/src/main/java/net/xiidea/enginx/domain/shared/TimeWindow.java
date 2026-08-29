package net.xiidea.enginx.domain.shared;

import java.time.Instant;

/**
 * The activation window of a proxy site. Either bound may be absent: no {@code activeFrom}
 * means "live as soon as it is deployed", no {@code expiresAt} means "never expires".
 */
public record TimeWindow(Instant activeFrom, Instant expiresAt) {

    private static final TimeWindow UNBOUNDED = new TimeWindow(null, null);

    public TimeWindow {
        if (activeFrom != null && expiresAt != null && !activeFrom.isBefore(expiresAt)) {
            throw new ValidationException("expiresAt", "expiresAt must be strictly after activeFrom");
        }
    }

    public static TimeWindow unbounded() {
        return UNBOUNDED;
    }

    public static TimeWindow until(Instant expiresAt) {
        return new TimeWindow(null, expiresAt);
    }

    public boolean hasExpiry() {
        return expiresAt != null;
    }

    public boolean hasExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean notYetStartedAt(Instant now) {
        return activeFrom != null && now.isBefore(activeFrom);
    }

    public boolean isOpenAt(Instant now) {
        return !hasExpiredAt(now) && !notYetStartedAt(now);
    }

    public TimeWindow withExpiresAt(Instant newExpiry) {
        return new TimeWindow(activeFrom, newExpiry);
    }

    public TimeWindow withoutExpiry() {
        return new TimeWindow(activeFrom, null);
    }
}
