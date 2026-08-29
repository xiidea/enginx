package net.xiidea.enginx.application.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Who gets told, and when.
 *
 * @param enabled           whether anything is sent at all
 * @param operatorAddresses the estate's own address book. Instance and outbox problems have no
 *                          natural owner, so without at least one of these they would be
 *                          discovered by whoever happens to look at a dashboard
 * @param notifyOwner       whether to also tell the person who created the site or certificate
 * @param expiryThresholds  days before expiry at which to warn, largest first
 * @param minimumSeverity   the floor below which nothing is delivered
 */
@ConfigurationProperties(prefix = "enginx.notifications")
public record NotificationProperties(
        boolean enabled,
        List<String> operatorAddresses,
        boolean notifyOwner,
        List<Integer> expiryThresholds,
        String minimumSeverity) {

    public NotificationProperties {
        operatorAddresses = operatorAddresses == null ? List.of() : List.copyOf(operatorAddresses);
        // 7/3/1 rather than a single warning: one notice a week out is easy to postpone and then
        // forget, and one notice a day out may arrive on a Saturday.
        expiryThresholds = expiryThresholds == null || expiryThresholds.isEmpty()
                ? List.of(7, 3, 1)
                : expiryThresholds.stream().distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        minimumSeverity = minimumSeverity == null || minimumSeverity.isBlank() ? "INFO" : minimumSeverity;
    }

    /** The largest threshold, which is how far ahead a scan needs to look. */
    public int widestThresholdDays() {
        return expiryThresholds.isEmpty() ? 0 : expiryThresholds.get(0);
    }
}
