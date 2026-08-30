package net.xiidea.enginx.application.notification;

import net.xiidea.enginx.domain.notification.NotificationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which channel carries which kind.
 *
 * <p>Most of these are about the default, because this is the subsystem that tells you when
 * something else is wrong: a routing mistake here is not noticed until the incident it was
 * supposed to warn about.
 */
class NotificationRoutingTest {

    private static NotificationProperties routing(Map<String, List<String>> routes) {
        return new NotificationProperties(true, List.of("ops@example.com"), true, List.of(7, 3, 1),
                "INFO", routes);
    }

    /**
     * The property that makes this safe to ship. Every channel carried everything before routing
     * existed, and a deployment that has never heard of it must keep doing exactly that.
     */
    @Test
    @DisplayName("a channel nobody has routed carries everything")
    void unroutedChannelsCarryEverything() {
        NotificationProperties properties = routing(Map.of());

        for (String channel : List.of("mail", "webhook", "log")) {
            assertThat(properties.kindsFor(channel))
                    .as(channel)
                    .containsExactlyInAnyOrder(NotificationKind.values());
        }
    }

    /**
     * Every setting in this application is written ${VAR:}, so an unset environment variable
     * arrives as an empty string rather than as an absent key. If empty meant "carry nothing",
     * upgrading into this version would silently stop all notifications — and nobody would find
     * out until the first thing that needed reporting went unreported.
     */
    @Test
    @DisplayName("an empty route means everything, not nothing")
    void emptyIsNotTheSameAsMuted() {
        NotificationProperties properties = routing(Map.of(
                "mail", List.of(""),
                "webhook", List.of(),
                "log", List.of("  ")));

        for (String channel : List.of("mail", "webhook", "log")) {
            assertThat(properties.kindsFor(channel))
                    .as(channel)
                    .containsExactlyInAnyOrder(NotificationKind.values());
        }
    }

    @Test
    @DisplayName("a named channel carries exactly what it names")
    void namedChannelsAreExhaustive() {
        NotificationProperties properties = routing(Map.of(
                "mail", List.of("SITE_EXPIRING", "SITE_EXPIRED"),
                "webhook", List.of("INSTANCE_OFFLINE", "INSTANCE_DRIFTED")));

        assertThat(properties.kindsFor("mail"))
                .containsExactlyInAnyOrder(NotificationKind.SITE_EXPIRING, NotificationKind.SITE_EXPIRED);
        assertThat(properties.kindsFor("webhook"))
                .containsExactlyInAnyOrder(NotificationKind.INSTANCE_OFFLINE, NotificationKind.INSTANCE_DRIFTED);
        // Unmentioned, so unchanged.
        assertThat(properties.kindsFor("log")).containsExactlyInAnyOrder(NotificationKind.values());
    }

    @Test
    @DisplayName("NONE mutes a channel without disabling it")
    void noneMutes() {
        NotificationProperties properties = routing(Map.of("webhook", List.of("NONE")));

        assertThat(properties.kindsFor("webhook")).isEmpty();
        assertThat(properties.kindsFor("mail")).containsExactlyInAnyOrder(NotificationKind.values());
    }

    @Test
    @DisplayName("names are read case-insensitively, and padding is ignored")
    void namesAreForgiving() {
        NotificationProperties properties = routing(Map.of("log", List.of(" site_expired ", "SITE_EXPIRING")));

        assertThat(properties.kindsFor("log"))
                .containsExactlyInAnyOrder(NotificationKind.SITE_EXPIRED, NotificationKind.SITE_EXPIRING);
    }

    /**
     * "Carry nothing, and also carry these" has no reading. Ignoring either half would be a guess
     * at which the operator meant, and both guesses are wrong half the time.
     */
    @Test
    @DisplayName("NONE combined with a kind is refused rather than guessed at")
    void muteCannotBeCombined() {
        assertThatThrownBy(() -> routing(Map.of("log", List.of("NONE", "SITE_EXPIRED"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be combined");
    }

    /**
     * Refused at startup rather than ignored. A channel configured with a typo would carry
     * nothing, and carrying nothing looks exactly like having nothing to say.
     */
    @Test
    @DisplayName("an unrecognised kind stops the application, and says what is valid")
    void unknownKindsAreRefused() {
        assertThatThrownBy(() -> routing(Map.of("mail", List.of("SITE_EXPIRING", "SITE_EXPIRD"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SITE_EXPIRD")
                .hasMessageContaining("enginx.notifications.routing.mail")
                .hasMessageContaining("SITE_EXPIRING");
    }
}
