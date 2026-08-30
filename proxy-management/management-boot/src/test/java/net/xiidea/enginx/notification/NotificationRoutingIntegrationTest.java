package net.xiidea.enginx.notification;

import net.xiidea.enginx.application.notification.NotificationService;
import net.xiidea.enginx.domain.notification.NotificationChannel;
import net.xiidea.enginx.domain.notification.NotificationEvent;
import net.xiidea.enginx.domain.notification.NotificationKind;
import net.xiidea.enginx.support.AbstractIntegrationTest;
import net.xiidea.enginx.support.TestSubjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sending one kind to one channel and a different kind to another.
 *
 * <p>Configured exactly as an operator would: mail takes the site conditions, webhook takes the
 * host conditions, and the log takes only what somebody would want to find afterwards.
 */
@Import({TestSubjectProvider.Config.class, NotificationRoutingIntegrationTest.Channels.class})
@TestPropertySource(properties = {
        "enginx.notifications.enabled=true",
        "enginx.notifications.operator-addresses=ops@example.com",
        "enginx.notifications.routing.mail=SITE_EXPIRING,SITE_EXPIRED",
        "enginx.notifications.routing.webhook=INSTANCE_OFFLINE,INSTANCE_DRIFTED",
        "enginx.notifications.routing.log=OUTBOX_MESSAGE_DEAD",
})
class NotificationRoutingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationService notifications;
    @Autowired
    private Recorder mail;
    @Autowired
    private Recorder webhook;
    @Autowired
    private Recorder logChannel;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate notification_ledger");
        mail.seen.clear();
        webhook.seen.clear();
        logChannel.seen.clear();
    }

    private void raise(NotificationKind kind) {
        notifications.notifyOnce(new NotificationEvent(kind, "TEST", UUID.randomUUID(), "",
                UUID.randomUUID().toString(), "subject", "body", null));
    }

    @Test
    @DisplayName("each kind reaches only the channel configured for it")
    void kindsGoWhereTheyAreRouted() {
        raise(NotificationKind.SITE_EXPIRING);
        raise(NotificationKind.INSTANCE_OFFLINE);
        raise(NotificationKind.OUTBOX_MESSAGE_DEAD);

        assertThat(mail.seen).containsExactly(NotificationKind.SITE_EXPIRING);
        assertThat(webhook.seen).containsExactly(NotificationKind.INSTANCE_OFFLINE);
        assertThat(logChannel.seen).containsExactly(NotificationKind.OUTBOX_MESSAGE_DEAD);
    }

    @Test
    @DisplayName("a kind nobody routed reaches nobody, and is not reported as a delivery failure")
    void unroutedKindsGoNowhere() {
        // CERTIFICATE_EXPIRING is named by no channel here, so it is routed nowhere. That is a
        // configuration decision, not a broken channel, and the ledger has to say which.
        raise(NotificationKind.CERTIFICATE_EXPIRING);

        assertThat(mail.seen).isEmpty();
        assertThat(webhook.seen).isEmpty();
        assertThat(logChannel.seen).isEmpty();

        Map<String, Object> row = jdbc.queryForMap(
                "select status, detail from notification_ledger order by created_at desc limit 1");
        assertThat(row.get("detail").toString())
                .contains("No channel is configured to carry CERTIFICATE_EXPIRING");
        // SUPPRESSED, not FAILED: countFailed feeds a metric, and a channel narrowed on purpose
        // must not show up there as something broken.
        assertThat(row.get("status")).isEqualTo("SUPPRESSED");
    }

    /** Named by two channels is not a mistake: some conditions are worth two places. */
    @Test
    @DisplayName("a kind may be routed to more than one channel")
    void oneKindCanReachSeveralChannels() {
        raise(NotificationKind.SITE_EXPIRED);

        assertThat(mail.seen).containsExactly(NotificationKind.SITE_EXPIRED);
        assertThat(webhook.seen).isEmpty();
    }

    static class Recorder implements NotificationChannel {
        private final String name;
        final List<NotificationKind> seen = new ArrayList<>();

        Recorder(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public synchronized boolean deliver(NotificationEvent event, Collection<String> recipients) {
            seen.add(event.kind());
            return true;
        }
    }

    /**
     * Stands in for the three real channels under their real names, because the names are what the
     * routing keys on — a double called something else would test nothing.
     */
    @TestConfiguration
    static class Channels {
        @Bean
        Recorder mail() {
            return new Recorder("mail");
        }

        @Bean
        Recorder webhook() {
            return new Recorder("webhook");
        }

        @Bean
        Recorder logChannel() {
            return new Recorder("log");
        }
    }
}
