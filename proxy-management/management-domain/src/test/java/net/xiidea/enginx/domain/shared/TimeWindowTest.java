package net.xiidea.enginx.domain.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowTest {

    private static final Instant NOON = Instant.parse("2026-08-28T12:00:00Z");

    @Test
    void rejectsAnInvertedWindow() {
        Instant later = NOON.plusSeconds(60);
        assertThatThrownBy(() -> new TimeWindow(later, NOON)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsAZeroLengthWindow() {
        assertThatThrownBy(() -> new TimeWindow(NOON, NOON)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("expiry is inclusive of the instant itself: at expires_at the site is already expired")
    void expiryIsInclusive() {
        TimeWindow window = TimeWindow.until(NOON);
        assertThat(window.hasExpiredAt(NOON.minusMillis(1))).isFalse();
        assertThat(window.hasExpiredAt(NOON)).isTrue();
    }

    @Test
    @DisplayName("activation is inclusive too: at active_from the site is live")
    void activationIsInclusive() {
        TimeWindow window = new TimeWindow(NOON, null);
        assertThat(window.notYetStartedAt(NOON.minusMillis(1))).isTrue();
        assertThat(window.notYetStartedAt(NOON)).isFalse();
    }

    @Test
    void unboundedIsAlwaysOpen() {
        assertThat(TimeWindow.unbounded().isOpenAt(NOON)).isTrue();
        assertThat(TimeWindow.unbounded().hasExpiry()).isFalse();
    }
}
