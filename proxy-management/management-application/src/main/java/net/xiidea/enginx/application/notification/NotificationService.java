package net.xiidea.enginx.application.notification;

import net.xiidea.enginx.domain.notification.NotificationChannel;
import net.xiidea.enginx.domain.notification.NotificationEvent;
import net.xiidea.enginx.domain.notification.NotificationLedger;
import net.xiidea.enginx.domain.notification.NotificationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sends a notification, once.
 *
 * <p>The order matters and is the whole design: claim first, then send. A crash between the two
 * loses one notification; claiming afterwards would instead re-send on every scan until the send
 * finally succeeded, which for a condition lasting four days is several thousand messages. Losing
 * one message is recoverable — the condition is still on the dashboard, in the metrics and in the
 * health endpoint. Burying the recipients is not: they stop reading the channel, and then the next
 * real one is missed too.
 */
@Service
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLedger ledger;
    private final List<NotificationChannel> channels;
    private final RecipientResolver recipients;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationService(NotificationLedger ledger, List<NotificationChannel> channels,
                               RecipientResolver recipients, NotificationProperties properties, Clock clock) {
        this.ledger = ledger;
        this.channels = channels;
        this.recipients = recipients;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Delivers this notification unless it has been delivered before.
     *
     * @return whether anything was sent
     */
    public boolean notifyOnce(NotificationEvent event) {
        if (!properties.enabled() || belowFloor(event)) {
            return false;
        }

        Optional<UUID> claim = ledger.claim(event, clock.instant());
        if (claim.isEmpty()) {
            // Already sent, or being sent right now by another node. Either way, not ours.
            return false;
        }
        UUID ledgerId = claim.get();

        // The operator list, the owner, and anyone subscribed to this particular resource. A set,
        // so somebody who is both an operator and a subscriber is told once.
        Set<String> addresses = new java.util.LinkedHashSet<>(recipients.resolve(event.ownerUsername()));
        addresses.addAll(event.subscribers());

        if (addresses.isEmpty()) {
            // Recorded rather than dropped silently. "Nobody is configured to be told" is a
            // configuration problem an operator needs to see, and the ledger is where they will
            // look when they wonder why they never heard about something.
            ledger.recordOutcome(ledgerId, false, "", "No recipients are configured");
            log.warn("Notification {} for {} had no recipients; set "
                    + "enginx.notifications.operator-addresses, or subscribe an address to the resource",
                    event.kind(), event.resourceId());
            return false;
        }

        return dispatch(event, ledgerId, addresses);
    }

    private boolean dispatch(NotificationEvent event, UUID ledgerId, Set<String> addresses) {
        // Configured, then routed. A channel carries everything unless it has been given a list,
        // so this is inert until somebody names one.
        List<NotificationChannel> enabled = channels.stream()
                .filter(NotificationChannel::isEnabled)
                .filter(channel -> properties.kindsFor(channel.name()).contains(event.kind()))
                .toList();

        if (enabled.isEmpty()) {
            // Routed nowhere by configuration, which is not the same as attempted and failed.
            // Recording it as a failure would send somebody looking for a broken channel.
            ledger.recordSuppressed(ledgerId,
                    "No channel is configured to carry " + event.kind());
            log.debug("Notification {} for {} is not routed to any channel",
                    event.kind(), event.resourceId());
            return false;
        }

        StringBuilder outcome = new StringBuilder();
        boolean anyDelivered = false;

        for (NotificationChannel channel : enabled) {
            boolean delivered;
            try {
                delivered = channel.deliver(event, addresses);
            } catch (RuntimeException e) {
                // A channel is not allowed to break the scan. The next condition still needs to be
                // found, and the other channels still need their chance at this one.
                delivered = false;
                log.warn("Notification channel {} threw: {}", channel.name(), e.toString());
                outcome.append(channel.name()).append("=threw; ");
            }
            anyDelivered |= delivered;
            if (!outcome.toString().contains(channel.name())) {
                outcome.append(channel.name()).append(delivered ? "=ok; " : "=failed; ");
            }
        }

        ledger.recordOutcome(ledgerId, anyDelivered, String.join(",", addresses), outcome.toString().trim());

        if (!anyDelivered) {
            log.warn("Notification {} for {} reached nobody: {}", event.kind(), event.resourceId(), outcome);
        }
        return anyDelivered;
    }

    private boolean belowFloor(NotificationEvent event) {
        NotificationSeverity floor;
        try {
            floor = NotificationSeverity.valueOf(properties.minimumSeverity().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            floor = NotificationSeverity.INFO;
        }
        return event.severity().ordinal() < floor.ordinal();
    }
}
