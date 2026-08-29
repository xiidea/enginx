package net.xiidea.enginx.infrastructure.notification;

import net.xiidea.enginx.domain.notification.NotificationChannel;
import net.xiidea.enginx.domain.notification.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Always on, and always last.
 *
 * <p>Its purpose is that a notification is never lost silently. In development there is no mail
 * server and no webhook, so without this a scan would find a real problem, mark it sent in the
 * ledger, and deliver it nowhere — and because the ledger deduplicates, it would never be sent
 * again. Writing it to the log costs nothing and means the record exists somewhere.
 *
 * <p>It reports success, which is deliberate: a notification that reached the log has reached
 * something durable. Treating the log as a failure would make every development scan look broken.
 */
@Component
@Order(Integer.MAX_VALUE)
public class LoggingNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger("net.xiidea.enginx.notifications");

    @Override
    public String name() {
        return "log";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean deliver(NotificationEvent event, Collection<String> recipients) {
        log.warn("[{}] {} → {}\n{}", event.severity(), event.subject(), recipients, event.body());
        return true;
    }
}
