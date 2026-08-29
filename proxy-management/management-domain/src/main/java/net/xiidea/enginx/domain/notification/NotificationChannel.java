package net.xiidea.enginx.domain.notification;

import java.util.Collection;

/**
 * Somewhere a notification can be delivered.
 *
 * <p>A port, so adding a chat integration later touches one module. Implementations must not throw
 * for an ordinary delivery failure — see {@link #deliver} — because the caller is a scheduled scan
 * and a mail server being down must not stop the platform noticing the next condition.
 */
public interface NotificationChannel {

    /** A short name, for logging and for the ledger's record of where something went. */
    String name();

    /** Whether this channel is configured. An unconfigured channel is skipped, not an error. */
    boolean isEnabled();

    /**
     * @return true when the notification was handed off successfully
     */
    boolean deliver(NotificationEvent event, Collection<String> recipients);
}
