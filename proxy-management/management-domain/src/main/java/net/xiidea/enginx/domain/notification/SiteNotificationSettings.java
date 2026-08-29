package net.xiidea.enginx.domain.notification;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who to tell about one site, and whether to tell them.
 *
 * <p>Separate from {@code ProxySiteSpec} on purpose. The spec is the input to the renderer and the
 * thing an audit trail calls "the site changed"; who receives an expiry warning is neither, and
 * folding it in would mean adding an address takes the site's optimistic lock and needs the
 * authority to alter routing.
 *
 * <p>Enabled by default, because that is what every site already does — the operator addresses are
 * told about everything. This is an opt-out and a way to widen the list, not a new thing to
 * configure before anything works.
 */
public final class SiteNotificationSettings {

    /**
     * Deliberately permissive.
     *
     * <p>The exhaustive grammar for an address admits things nobody types and rejects things that
     * work; the platform is not the last line of defence here, since a wrong-but-valid address
     * fails at delivery either way. This catches the mistakes a person actually makes — a missing
     * @, a trailing comma from pasting a list, whitespace in the middle.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");

    /** Enough for a team. Beyond this it is a mailing list, which is one address. */
    private static final int MAX_SUBSCRIBERS = 20;

    private final UUID proxySiteId;
    private boolean expiryEnabled;
    private Set<String> subscribers;
    private String updatedBy;
    private Instant updatedAt;

    private SiteNotificationSettings(UUID proxySiteId, boolean expiryEnabled, Set<String> subscribers,
                                     String updatedBy, Instant updatedAt) {
        this.proxySiteId = proxySiteId;
        this.expiryEnabled = expiryEnabled;
        this.subscribers = subscribers;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /**
     * What a site has before anybody configures it.
     *
     * <p>Returned rather than stored, so a site nobody has touched needs no row: absence and
     * "the defaults" are the same state, and writing one row per site to say nothing would make
     * every site creation carry a second insert for no information.
     */
    public static SiteNotificationSettings defaultsFor(UUID proxySiteId) {
        return new SiteNotificationSettings(proxySiteId, true, Set.of(), null, null);
    }

    public static SiteNotificationSettings rehydrate(UUID proxySiteId, boolean expiryEnabled,
                                                     Set<String> subscribers, String updatedBy,
                                                     Instant updatedAt) {
        return new SiteNotificationSettings(proxySiteId, expiryEnabled,
                subscribers == null ? Set.of() : Set.copyOf(subscribers), updatedBy, updatedAt);
    }

    /**
     * Replaces the settings wholesale.
     *
     * <p>Whole-list rather than add and remove one at a time: the console shows the list and sends
     * back what it shows, so two people editing at once cannot end up with a union of their two
     * intentions that neither of them chose.
     */
    public void configure(boolean expiryEnabled, Set<String> newSubscribers, String actor, Instant now) {
        this.expiryEnabled = expiryEnabled;
        this.subscribers = normalise(newSubscribers);
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    private static Set<String> normalise(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String candidate : raw) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            // Lowercased so the same person cannot be added twice under different capitalisation
            // and then told twice about the same expiry.
            String address = candidate.trim().toLowerCase(Locale.ROOT);
            if (!EMAIL.matcher(address).matches()) {
                throw new ValidationException("subscribers", "'" + candidate + "' is not an email address");
            }
            cleaned.add(address);
        }
        if (cleaned.size() > MAX_SUBSCRIBERS) {
            throw new ValidationException("subscribers",
                    "At most " + MAX_SUBSCRIBERS + " addresses. Beyond that, subscribe a mailing list.");
        }
        return Set.copyOf(cleaned);
    }

    /** Whether this site's expiry warnings should be sent at all. */
    public boolean expiryEnabled() {
        return expiryEnabled;
    }

    public UUID proxySiteId() {
        return proxySiteId;
    }

    public Set<String> subscribers() {
        return subscribers;
    }

    public String updatedBy() {
        return updatedBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** True when nothing has been changed from the defaults, so nothing needs storing. */
    public boolean isDefault() {
        return expiryEnabled && subscribers.isEmpty();
    }
}
