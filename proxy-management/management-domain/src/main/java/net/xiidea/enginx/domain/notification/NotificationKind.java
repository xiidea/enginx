package net.xiidea.enginx.domain.notification;

/**
 * The conditions worth telling a person about.
 *
 * <p>Each one is something the platform already knows and already exposes through health or
 * metrics. This is the push channel for the subset that has a deadline: by the time someone
 * notices an expired certificate on a dashboard, the browser error has already happened.
 */
public enum NotificationKind {

    /** A site's expiry is approaching. Sent at each configured threshold. */
    SITE_EXPIRING(NotificationSeverity.WARNING),
    /** A site's window has closed and it has been taken off the air. */
    SITE_EXPIRED(NotificationSeverity.CRITICAL),

    CERTIFICATE_EXPIRING(NotificationSeverity.WARNING),
    /**
     * Renewal ran and did not produce a certificate. The distinction from expiry matters: this
     * one says the mechanism that should prevent expiry is itself broken.
     */
    CERTIFICATE_RENEWAL_FAILED(NotificationSeverity.CRITICAL),

    /** An agent has not been heard from. The host keeps serving; it can no longer be changed. */
    INSTANCE_OFFLINE(NotificationSeverity.WARNING),
    /** A host is serving a configuration the platform did not deploy. */
    INSTANCE_DRIFTED(NotificationSeverity.WARNING),

    /** A change was accepted by the API and will now never reach its host without help. */
    OUTBOX_MESSAGE_DEAD(NotificationSeverity.CRITICAL);

    private final NotificationSeverity severity;

    NotificationKind(NotificationSeverity severity) {
        this.severity = severity;
    }

    public NotificationSeverity severity() {
        return severity;
    }
}
