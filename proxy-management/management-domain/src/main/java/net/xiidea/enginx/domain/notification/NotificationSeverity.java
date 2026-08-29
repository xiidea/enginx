package net.xiidea.enginx.domain.notification;

/**
 * How much attention a notification is asking for.
 *
 * <p>Carried so a channel can decide what to do with it — subject prefix, colour, or whether to
 * deliver at all below a configured floor. Deliberately three levels: a scale with more gradations
 * than people can act on turns into everything being sent at the highest one.
 */
public enum NotificationSeverity {
    /** Worth knowing. Nothing is broken. */
    INFO,
    /** Something will break unless someone acts, and there is time to act. */
    WARNING,
    /** Something is broken now, or will be before the next working day. */
    CRITICAL
}
