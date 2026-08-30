package net.xiidea.enginx.application.notification;

import net.xiidea.enginx.domain.notification.NotificationKind;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        String minimumSeverity,
        Map<String, List<String>> routing) {

    public NotificationProperties {
        operatorAddresses = operatorAddresses == null ? List.of() : List.copyOf(operatorAddresses);
        // 7/3/1 rather than a single warning: one notice a week out is easy to postpone and then
        // forget, and one notice a day out may arrive on a Saturday.
        expiryThresholds = expiryThresholds == null || expiryThresholds.isEmpty()
                ? List.of(7, 3, 1)
                : expiryThresholds.stream().distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        minimumSeverity = minimumSeverity == null || minimumSeverity.isBlank() ? "INFO" : minimumSeverity;
        routing = routing == null ? Map.of() : Map.copyOf(routing);
        // Parsed at startup so a typo is a refusal to start rather than a channel that silently
        // carries nothing. A misrouted notification is only noticed during the incident it was
        // supposed to warn about.
        validateRouting(routing);
    }

    /**
     * Resolves every route at startup, so a configuration mistake stops the application here
     * rather than throwing on the first notification somebody was relying on.
     */
    private static void validateRouting(Map<String, List<String>> routing) {
        for (Map.Entry<String, List<String>> entry : routing.entrySet()) {
            List<String> named = entry.getValue() == null ? List.<String>of() : entry.getValue().stream()
                    .filter(raw -> raw != null && !raw.isBlank())
                    .toList();

            boolean mutes = named.stream().anyMatch(raw -> NONE.equalsIgnoreCase(raw.trim()));
            if (mutes && named.size() > 1) {
                // "Carry nothing, and also carry these" has no reading. Ignoring either half would
                // be a guess at which the operator meant, and both guesses are wrong half the time.
                throw new IllegalStateException("enginx.notifications.routing." + entry.getKey()
                        + " lists " + NONE + " alongside " + (named.size() - 1) + " kind(s). "
                        + NONE + " mutes a channel and cannot be combined with anything.");
            }
            if (mutes) {
                continue;
            }
            named.forEach(kind -> parseKind(kind, entry.getKey()));
        }
    }

    private static NotificationKind parseKind(String raw, String channel) {
        try {
            return NotificationKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("enginx.notifications.routing." + channel + " names '"
                    + raw.trim() + "', which is not a notification kind. Valid kinds: "
                    + Arrays.stream(NotificationKind.values()).map(Enum::name).sorted().toList());
        }
    }

    /** Mutes a channel without disabling it. Empty cannot mean this — see {@link #kindsFor}. */
    public static final String NONE = "NONE";

    /**
     * Which kinds a channel should carry.
     *
     * <p>Unset carries everything, which is what every channel did before routing existed — so
     * adding this configuration to an existing deployment changes nothing until somebody fills
     * something in.
     *
     * <p>Empty counts as unset, and that is not a detail. Every setting in this application is
     * written {@code ${VAR:}}, so an unset environment variable arrives here as an empty string
     * rather than as an absent key. If empty meant "carry nothing", a deployment that had never
     * heard of routing would silently stop sending anything the moment it took this version —
     * which is the worst possible failure for the subsystem whose job is to tell you when
     * something is wrong.
     *
     * <p>Muting a channel is therefore explicit: {@code NONE}.
     */
    public Set<NotificationKind> kindsFor(String channel) {
        List<String> configured = routing.get(channel);
        List<String> named = configured == null ? List.of() : configured.stream()
                .filter(raw -> raw != null && !raw.isBlank())
                .toList();

        if (named.isEmpty()) {
            return Set.of(NotificationKind.values());
        }
        if (named.size() == 1 && NONE.equalsIgnoreCase(named.get(0).trim())) {
            return Set.of();
        }

        Set<NotificationKind> kinds = new LinkedHashSet<>();
        for (String raw : named) {
            kinds.add(parseKind(raw, channel));
        }
        return Set.copyOf(kinds);
    }

    /** Whether any channel has been given an explicit route, for logging the configuration once. */
    public Map<String, Set<NotificationKind>> configuredRouting() {
        Map<String, Set<NotificationKind>> resolved = new LinkedHashMap<>();
        routing.keySet().forEach(channel -> resolved.put(channel, kindsFor(channel)));
        return Map.copyOf(resolved);
    }

    /** The largest threshold, which is how far ahead a scan needs to look. */
    public int widestThresholdDays() {
        return expiryThresholds.isEmpty() ? 0 : expiryThresholds.get(0);
    }
}
