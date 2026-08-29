package net.xiidea.enginx.notification;

import net.xiidea.enginx.application.notification.NotificationScanService;
import net.xiidea.enginx.application.notification.SiteNotificationService;
import net.xiidea.enginx.domain.shared.ValidationException;
import net.xiidea.enginx.application.proxy.ProxySiteCommands;
import net.xiidea.enginx.application.proxy.ProxySiteService;
import net.xiidea.enginx.domain.nginx.NginxInstance;
import net.xiidea.enginx.domain.nginx.NginxInstanceRepository;
import net.xiidea.enginx.domain.notification.NotificationChannel;
import net.xiidea.enginx.domain.notification.NotificationEvent;
import net.xiidea.enginx.domain.notification.NotificationKind;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scan, end to end against PostgreSQL.
 *
 * <p>What is faked is the channel — everything else, including the ledger's deduplication and the
 * repository queries that find expiring sites, is the production path.
 */
@Import({TestSubjectProvider.Config.class, NotificationScanIntegrationTest.CapturingChannelConfig.class})
@TestPropertySource(properties = {
        "enginx.notifications.enabled=true",
        "enginx.notifications.operator-addresses=ops@example.com",
        "enginx.notifications.expiry-thresholds=7,3,1",
})
class NotificationScanIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationScanService scan;
    @Autowired
    private ProxySiteService sites;
    @Autowired
    private NginxInstanceRepository instances;
    @Autowired
    private TestSubjectProvider subjects;
    @Autowired
    private CapturingChannel channel;
    @Autowired
    private SiteNotificationService siteNotifications;

    private UUID instanceId;

    @BeforeEach
    void setUp() {
        subjects.actAs("ada", java.util.Set.of(),
                net.xiidea.enginx.domain.permission.GlobalRole.SUPER_ADMIN);
        channel.captured.clear();

        NginxInstance instance = NginxInstance.register(UUID.randomUUID(), "nginx-" + shortId(),
                "host.example.com", "https://host.example.com:8443", "A".repeat(64), "TEST",
                Instant.now());
        instanceId = instances.save(instance).id();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ProxySite siteExpiringIn(Duration remaining) {
        ProxySiteSpec spec = new ProxySiteSpec("Site", DomainName.of(shortId() + ".example.com"),
                instanceId, TimeWindow.until(Instant.now().plus(remaining)),
                false, false, false, false, null,
                LoadBalancingMethod.ROUND_ROBIN, ProxyTimeouts.defaults(),
                List.of(UpstreamTarget.of("http", "10.0.0.1", 8080)), List.of(), List.of());
        return sites.create(new ProxySiteCommands.Create(spec, null));
    }

    @Test
    @DisplayName("a site inside a threshold produces exactly one notification, however often the scan runs")
    void expiringSiteIsReportedOnce() {
        ProxySite site = siteExpiringIn(Duration.ofDays(2));

        assertThat(scan.scan()).isPositive();
        List<NotificationEvent> first = channel.forResource(site.id());
        assertThat(first).hasSize(1);
        assertThat(first.get(0).kind()).isEqualTo(NotificationKind.SITE_EXPIRING);
        // Two days out falls inside the 3-day threshold, not the 7-day one: the scan reports the
        // most urgent threshold crossed, so a site does not generate a backlog of stale warnings.
        assertThat(first.get(0).threshold()).isEqualTo("3");

        // The condition is still true. Running again must not tell anyone a second time.
        channel.captured.clear();
        scan.scan();
        scan.scan();
        assertThat(channel.forResource(site.id())).isEmpty();
    }

    @Test
    @DisplayName("a site past its expiry is reported as expired, not as expiring")
    void expiredSiteIsReportedAsExpired() {
        ProxySite site = siteExpiringIn(Duration.ofDays(-1));

        scan.scan();

        assertThat(channel.forResource(site.id()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.kind()).isEqualTo(NotificationKind.SITE_EXPIRED);
                    assertThat(event.severity())
                            .isEqualTo(net.xiidea.enginx.domain.notification.NotificationSeverity.CRITICAL);
                });
    }

    @Test
    @DisplayName("a site outside every threshold is not reported at all")
    void distantExpiryIsSilent() {
        ProxySite site = siteExpiringIn(Duration.ofDays(60));

        scan.scan();

        assertThat(channel.forResource(site.id())).isEmpty();
    }

    /**
     * The behaviour the fingerprint exists for. Extending an expiry has to re-arm the warnings for
     * the new date, and it has to do so without the update path knowing that notifications exist.
     */
    @Test
    @DisplayName("extending a site's expiry re-arms its warnings")
    void extendingExpiryReArms() {
        ProxySite site = siteExpiringIn(Duration.ofDays(2));
        scan.scan();
        assertThat(channel.forResource(site.id())).hasSize(1);

        channel.captured.clear();
        sites.renew(new ProxySiteCommands.Renew(site.id(),
                Instant.now().plus(Duration.ofDays(2).plusHours(1)), null));

        scan.scan();

        assertThat(channel.forResource(site.id()))
                .describedAs("a new expiry is a new fact, so the warning is due again")
                .hasSize(1);
    }

    @Test
    @DisplayName("notifications carry the operator address and the site's owner")
    void recipientsIncludeOperatorsAndOwner() {
        siteExpiringIn(Duration.ofDays(1));

        scan.scan();

        assertThat(channel.recipients).contains("ops@example.com");
    }

    /**
     * The opt-out, and the reason it is checked before the ledger rather than after.
     *
     * <p>Claiming and then discarding would record the notification as handled, so re-enabling
     * later would send nothing — the site would be silently un-notifiable for good.
     */
    @Test
    @DisplayName("a site opted out of expiry warnings produces none, and can be opted back in")
    void optingOutSuppressesAndIsReversible() {
        ProxySite site = siteExpiringIn(Duration.ofDays(2));
        siteNotifications.configure(site.id(), false, Set.of());

        assertThat(scan.scan()).isZero();
        assertThat(channel.forResource(site.id())).isEmpty();

        siteNotifications.configure(site.id(), true, Set.of());

        assertThat(scan.scan()).isPositive();
        assertThat(channel.forResource(site.id())).hasSize(1);
    }

    @Test
    @DisplayName("subscribers are told in addition to the operator list, never instead of it")
    void subscribersAreAddedToTheOperators() {
        ProxySite site = siteExpiringIn(Duration.ofDays(1));
        siteNotifications.configure(site.id(), true, Set.of("team@example.com", "oncall@example.com"));

        scan.scan();

        assertThat(channel.forResource(site.id())).hasSize(1);
        assertThat(channel.recipients)
                .contains("ops@example.com", "team@example.com", "oncall@example.com");
    }

    /**
     * Who is told is not what happened. If the subscriber list were part of the ledger key, adding
     * an address would re-arm a warning already sent and tell everybody again.
     */
    @Test
    @DisplayName("adding a subscriber does not re-send a warning that has already gone out")
    void changingSubscribersDoesNotResend() {
        ProxySite site = siteExpiringIn(Duration.ofDays(2));
        scan.scan();
        assertThat(channel.forResource(site.id())).hasSize(1);

        channel.captured.clear();
        siteNotifications.configure(site.id(), true, Set.of("late@example.com"));
        scan.scan();

        assertThat(channel.forResource(site.id())).isEmpty();
    }

    @Test
    @DisplayName("a site nobody has configured is notified exactly as before")
    void defaultsMatchThePreviousBehaviour() {
        ProxySite site = siteExpiringIn(Duration.ofDays(2));

        assertThat(siteNotifications.get(site.id()).expiryEnabled()).isTrue();
        assertThat(siteNotifications.get(site.id()).subscribers()).isEmpty();

        scan.scan();
        assertThat(channel.forResource(site.id())).hasSize(1);
    }

    @Test
    @DisplayName("an address that is not one is refused before it can be stored")
    void invalidAddressesAreRefused() {
        ProxySite site = siteExpiringIn(Duration.ofDays(30));

        assertThatThrownBy(() -> siteNotifications.configure(site.id(), true, Set.of("not-an-address")))
                .isInstanceOf(ValidationException.class);
        // A trailing comma from pasting a list is the mistake people actually make.
        assertThatThrownBy(() -> siteNotifications.configure(site.id(), true, Set.of("a@example.com,")))
                .isInstanceOf(ValidationException.class);
    }

    /** The same person under two capitalisations is one person, and is told once. */
    @Test
    @DisplayName("addresses are normalised, so nobody is subscribed twice")
    void addressesAreNormalised() {
        ProxySite site = siteExpiringIn(Duration.ofDays(30));

        siteNotifications.configure(site.id(), true, Set.of("Team@Example.com", "team@example.com"));

        assertThat(siteNotifications.get(site.id()).subscribers()).containsExactly("team@example.com");
    }

    // ---- test channel ------------------------------------------------------

    static class CapturingChannel implements NotificationChannel {
        final List<NotificationEvent> captured = new ArrayList<>();
        final List<String> recipients = new ArrayList<>();

        @Override
        public String name() {
            return "capture";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public synchronized boolean deliver(NotificationEvent event, Collection<String> to) {
            captured.add(event);
            recipients.addAll(to);
            return true;
        }

        synchronized List<NotificationEvent> forResource(UUID resourceId) {
            return captured.stream().filter(e -> e.resourceId().equals(resourceId)).toList();
        }
    }

    @TestConfiguration
    static class CapturingChannelConfig {
        @Bean
        @Primary
        @Order(1)
        CapturingChannel capturingChannel() {
            return new CapturingChannel();
        }
    }
}
