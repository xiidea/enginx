package net.xiidea.enginx.infrastructure.acme;

import net.xiidea.enginx.domain.certificate.AcmeChallengePublisher;
import net.xiidea.enginx.domain.certificate.CertificateIssuanceException;
import net.xiidea.enginx.domain.certificate.CertificateRequest;
import net.xiidea.enginx.domain.certificate.DnsChallengePublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parts of the ACME provider that decide without contacting an authority.
 *
 * <p>Whether a request can succeed at all is worth deciding locally: every rejected order counts
 * against rate limits that are measured in requests per week.
 */
class AcmeCertificateProviderTest {

    private final AtomicInteger published = new AtomicInteger();

    private final AcmeChallengePublisher publisher = new AcmeChallengePublisher() {
        @Override
        public void publish(String token, String authorization) {
            published.incrementAndGet();
        }

        @Override
        public void withdraw(String token) {
        }
    };

    /** No DNS provider, which is the default deployment and the one these tests describe. */
    private static final DnsChallengePublisher NO_DNS =
            new DnsChallengeConfiguration().unconfiguredDnsChallengePublisher();

    /** A DNS provider that is present but never actually called by these tests. */
    private static final DnsChallengePublisher DNS_AVAILABLE = new DnsChallengePublisher() {
        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void publish(String recordName, String value) {
        }

        @Override
        public void withdraw(String recordName) {
        }
    };

    @Test
    @DisplayName("a wildcard is refused before any order is placed")
    void wildcardsAreRefusedWithoutContactingTheAuthority() {
        // A directory URL that cannot be reached: if the provider tried, the failure would be a
        // connection error rather than the refusal this asserts.
        AcmeCertificateProvider provider = new AcmeCertificateProvider(
                new AcmeProperties("http://localhost:1/dir", null, true, null, null, 2048),
                null, publisher, NO_DNS, false);

        assertThatThrownBy(() -> provider.issue(CertificateRequest.of("wild", List.of("*.example.com"))))
                .isInstanceOf(CertificateIssuanceException.class)
                .hasMessageContaining("DNS-01");

        assertThat(published.get())
                .describedAs("no challenge should be published for a request that cannot succeed")
                .isZero();
    }

    @Test
    @DisplayName("the refusal explains what to do instead")
    void theRefusalIsActionable() {
        AcmeCertificateProvider provider = new AcmeCertificateProvider(
                new AcmeProperties("http://localhost:1/dir", null, true, null, null, 2048),
                null, publisher, NO_DNS, false);

        assertThatThrownBy(() -> provider.issue(CertificateRequest.of("wild", List.of("*.example.com"))))
                .hasMessageContaining("List the subdomains explicitly");
    }

    /**
     * The refusal is about capability, not about wildcards being unwelcome. With a DNS provider
     * configured the request must proceed far enough to contact the authority — which here means
     * failing on the unreachable directory URL rather than on the pre-flight check.
     */
    @Test
    @DisplayName("a wildcard is attempted once a DNS provider is configured")
    void wildcardsProceedWhenDnsIsAvailable() {
        AcmeCertificateProvider provider = new AcmeCertificateProvider(
                new AcmeProperties("http://localhost:1/dir", null, true, null, null, 2048),
                null, publisher, DNS_AVAILABLE, false);

        // Any failure but the pre-flight refusal proves it got as far as contacting the authority.
        // Here that is the unreachable directory URL, which is the point: the refusal is about
        // capability, not about wildcards being unwelcome.
        assertThatThrownBy(() -> provider.issue(CertificateRequest.of("wild", List.of("*.example.com"))))
                .hasMessageNotContaining("no DNS provider is configured");
    }

    @Test
    @DisplayName("staging is the default directory, so a misconfiguration cannot hit production limits")
    void defaultsToStaging() {
        AcmeProperties properties = new AcmeProperties(null, null, false, null, null, 0);

        assertThat(properties.directoryUrl()).contains("staging");
        // Terms are never accepted implicitly.
        assertThat(properties.acceptTermsOfService()).isFalse();
        // A key smaller than 2048 bits is raised rather than honoured.
        assertThat(properties.keySize()).isEqualTo(2048);
    }
}
