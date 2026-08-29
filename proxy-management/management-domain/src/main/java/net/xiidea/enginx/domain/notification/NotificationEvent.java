package net.xiidea.enginx.domain.notification;

import java.util.UUID;

/**
 * One thing worth telling someone about.
 *
 * @param threshold      which threshold triggered this, as a plain string ("7", "3", "1"), or
 *                       {@code ""} when the kind has no thresholds. Never null: it is part of the
 *                       deduplication key, and a null there would let every scan send again.
 * @param fingerprint    identifies the underlying fact. The same condition with the same
 *                       fingerprint is sent once and never again; a changed fingerprint re-arms
 *                       it. Extending a site's expiry, or an agent recovering and later failing
 *                       again, both work by changing this rather than by clearing a flag.
 * @param ownerUsername  who owns the resource, if anyone does. Resolved to an address by the
 *                       dispatcher, which is why this is a username and not an email.
 */
public record NotificationEvent(
        NotificationKind kind,
        String resourceType,
        UUID resourceId,
        String threshold,
        String fingerprint,
        String subject,
        String body,
        String ownerUsername) {

    public NotificationEvent {
        threshold = threshold == null ? "" : threshold;
        if (kind == null || resourceId == null) {
            throw new IllegalArgumentException("kind and resourceId are required");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            // A blank fingerprint would make every occurrence look identical forever, so the
            // notification would fire once and then never again for the life of the resource.
            throw new IllegalArgumentException("a fingerprint is required; it is what re-arms the notification");
        }
    }

    public NotificationSeverity severity() {
        return kind.severity();
    }
}
