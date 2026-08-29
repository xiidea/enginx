package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ConflictException;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lifecycle function of architecture decision AD-6, exercised across the whole matrix. */
class ProxySiteLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final Instant YESTERDAY = NOW.minusSeconds(86_400);
    private static final Instant TOMORROW = NOW.plusSeconds(86_400);
    private static final UUID INSTANCE = UUID.randomUUID();

    @Nested
    class StatusDerivation {

        @Test
        void openWindowAndHealthyDeploymentIsActive() {
            assertThat(site(new TimeWindow(YESTERDAY, TOMORROW)).deriveStatus(NOW, false))
                    .isEqualTo(SiteStatus.ACTIVE);
        }

        @Test
        void windowNotYetOpenIsPending() {
            assertThat(site(new TimeWindow(TOMORROW, null)).deriveStatus(NOW, false))
                    .isEqualTo(SiteStatus.PENDING);
        }

        @Test
        void pastExpiryIsExpired() {
            assertThat(site(TimeWindow.until(YESTERDAY)).deriveStatus(NOW, false))
                    .isEqualTo(SiteStatus.EXPIRED);
        }

        @Test
        void failedDeploymentIsError() {
            assertThat(site(TimeWindow.unbounded()).deriveStatus(NOW, true)).isEqualTo(SiteStatus.ERROR);
        }

        @Test
        @DisplayName("a user's disable outranks every other signal, including a failed deployment")
        void disabledWins() {
            ProxySite site = site(TimeWindow.until(YESTERDAY));
            site.disable("ada", NOW);
            assertThat(site.deriveStatus(NOW, true)).isEqualTo(SiteStatus.DISABLED);
        }

        @Test
        @DisplayName("expiry outranks a pending window, so a window entirely in the past reads as expired")
        void expiryOutranksPending() {
            TimeWindow past = new TimeWindow(YESTERDAY.minusSeconds(60), YESTERDAY);
            assertThat(site(past).deriveStatus(NOW, false)).isEqualTo(SiteStatus.EXPIRED);
        }
    }

    @Test
    @DisplayName("re-enabling an expired site leaves it expired until the expiry is actually extended")
    void enableDoesNotResurrectAnExpiredSite() {
        ProxySite site = site(TimeWindow.until(YESTERDAY));
        site.disable("ada", NOW);
        site.enable("ada", NOW);
        assertThat(site.status()).isEqualTo(SiteStatus.EXPIRED);

        site.renewUntil(TOMORROW, "ada", NOW);
        assertThat(site.status()).isEqualTo(SiteStatus.ACTIVE);
        assertThat(site.window().expiresAt()).isEqualTo(TOMORROW);
    }

    @Test
    void renewingIntoThePastIsRejected() {
        ProxySite site = site(TimeWindow.until(TOMORROW));
        assertThatThrownBy(() -> site.renewUntil(YESTERDAY, "ada", NOW))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be in the future");
    }

    @Test
    void removingTheExpiryReactivatesAnExpiredSite() {
        ProxySite site = site(TimeWindow.until(YESTERDAY));
        assertThat(site.status()).isEqualTo(SiteStatus.EXPIRED);

        site.clearExpiry("ada", NOW);

        assertThat(site.window().hasExpiry()).isFalse();
        assertThat(site.status()).isEqualTo(SiteStatus.ACTIVE);
    }

    @Test
    @DisplayName("a clone starts disabled so a half-edited duplicate cannot take traffic")
    void clonesStartDisabled() {
        ProxySite source = site(TimeWindow.unbounded());
        ProxySite copy = source.cloneAs(UUID.randomUUID(), "Checkout copy",
                DomainName.of("app2.example.com"), "ada", NOW);

        assertThat(copy.adminState()).isEqualTo(AdminState.DISABLED);
        assertThat(copy.status()).isEqualTo(SiteStatus.DISABLED);
        assertThat(copy.domain().value()).isEqualTo("app2.example.com");
        assertThat(copy.id()).isNotEqualTo(source.id());
        assertThat(copy.spec().upstreams()).isEqualTo(source.spec().upstreams());
    }

    @Test
    @DisplayName("a site cannot be moved between NGINX instances by an update")
    void updateCannotChangeInstance() {
        ProxySite site = site(TimeWindow.unbounded());
        ProxySiteSpec elsewhere = specFor(TimeWindow.unbounded(), UUID.randomUUID());

        assertThatThrownBy(() -> site.update(elsewhere, "ada", NOW))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Clone it onto the target instance");
    }

    @Test
    void refreshStatusReportsWhetherAnythingChanged() {
        ProxySite site = site(TimeWindow.until(TOMORROW));
        assertThat(site.status()).isEqualTo(SiteStatus.ACTIVE);

        assertThat(site.refreshStatus(NOW, false)).isFalse();
        assertThat(site.refreshStatus(TOMORROW.plusSeconds(1), false)).isTrue();
        assertThat(site.status()).isEqualTo(SiteStatus.EXPIRED);
    }

    private static ProxySite site(TimeWindow window) {
        return ProxySite.create(UUID.randomUUID(), specFor(window, INSTANCE), AdminState.ENABLED, "ada", NOW);
    }

    private static ProxySiteSpec specFor(TimeWindow window, UUID instanceId) {
        return new ProxySiteSpec("Checkout", DomainName.of("app.example.com"), instanceId, window,
                false, false, false, false, null, LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.10.10.20", 8080)), List.of(), List.of());
    }
}
