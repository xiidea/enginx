package net.xiidea.enginx.notification;

import net.xiidea.enginx.domain.notification.NotificationEvent;
import net.xiidea.enginx.domain.notification.NotificationKind;
import net.xiidea.enginx.domain.notification.NotificationLedger;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deduplication, against a real database.
 *
 * <p>This is the part of the notification subsystem that decides whether it is useful or whether
 * everyone filters it away, and it rests entirely on a unique constraint. Testing it against a
 * mock would be testing the mock.
 */
class NotificationLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationLedger ledger;

    private static NotificationEvent event(UUID resourceId, String threshold, String fingerprint) {
        return new NotificationEvent(NotificationKind.SITE_EXPIRING, "PROXY_SITE", resourceId,
                threshold, fingerprint, "subject", "body", "ada");
    }

    @Test
    @DisplayName("the same condition is claimed once and never again")
    void claimsOnce() {
        UUID site = UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(ledger.claim(event(site, "7", "expiry-1"), now)).isPresent();
        assertThat(ledger.claim(event(site, "7", "expiry-1"), now)).isEmpty();
        assertThat(ledger.claim(event(site, "7", "expiry-1"), now.plusSeconds(86_400))).isEmpty();
    }

    @Test
    @DisplayName("each threshold is its own notification, so 7, 3 and 1 all get through")
    void thresholdsAreIndependent() {
        UUID site = UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(ledger.claim(event(site, "7", "expiry-1"), now)).isPresent();
        assertThat(ledger.claim(event(site, "3", "expiry-1"), now)).isPresent();
        assertThat(ledger.claim(event(site, "1", "expiry-1"), now)).isPresent();
    }

    /**
     * The property the whole design turns on. Extending a site's expiry has to re-arm its warnings
     * without any code path remembering to clear a flag — that version works until someone adds a
     * second way to change an expiry and forgets.
     */
    @Test
    @DisplayName("a changed fingerprint re-arms the notification")
    void changedFingerprintReArms() {
        UUID site = UUID.randomUUID();
        Instant now = Instant.now();

        assertThat(ledger.claim(event(site, "7", "expiry-1"), now)).isPresent();
        assertThat(ledger.claim(event(site, "7", "expiry-1"), now)).isEmpty();

        // The operator pushed the expiry out. The 7-day warning is due again for the new date.
        assertThat(ledger.claim(event(site, "7", "expiry-2"), now)).isPresent();
    }

    @Test
    @DisplayName("two resources in the same condition are two notifications")
    void resourcesAreIndependent() {
        Instant now = Instant.now();
        assertThat(ledger.claim(event(UUID.randomUUID(), "7", "expiry-1"), now)).isPresent();
        assertThat(ledger.claim(event(UUID.randomUUID(), "7", "expiry-1"), now)).isPresent();
    }

    /**
     * Two scheduler nodes scanning at the same moment must not both send. A read-then-write would
     * pass every test above and fail here — rarely, and therefore in a way nobody reproduces.
     */
    @Test
    @DisplayName("concurrent claims across threads yield exactly one winner")
    void concurrentClaimsYieldOneWinner() throws Exception {
        UUID site = UUID.randomUUID();
        Instant now = Instant.now();
        int racers = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Callable<Optional<UUID>>> attempts = java.util.Collections.nCopies(racers,
                    () -> ledger.claim(event(site, "1", "same-fingerprint"), now));

            long winners = 0;
            for (Future<Optional<UUID>> future : pool.invokeAll(attempts)) {
                if (future.get().isPresent()) {
                    winners++;
                }
            }
            assertThat(winners)
                    .describedAs("exactly one of %d concurrent claims may send", racers)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a delivery failure is recorded, so a silently broken channel is visible")
    void failureIsRecorded() {
        UUID site = UUID.randomUUID();
        long before = ledger.countFailed();

        UUID claimed = ledger.claim(event(site, "7", "fp"), Instant.now()).orElseThrow();
        ledger.recordOutcome(claimed, false, "ops@example.com", "mail=failed");

        assertThat(ledger.countFailed()).isEqualTo(before + 1);
    }
}
