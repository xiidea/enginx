package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;
import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxySiteSpecTest {

    private static final UUID INSTANCE = UUID.randomUUID();

    @Test
    void defaultsToASingleRootLocationAndRoundRobin() {
        ProxySiteSpec spec = spec().build();
        assertThat(spec.locations()).containsExactly(LocationRule.root());
        assertThat(spec.loadBalancingMethod()).isEqualTo(LoadBalancingMethod.ROUND_ROBIN);
        assertThat(spec.timeouts()).isEqualTo(ProxyTimeouts.defaults());
    }

    @Test
    void requiresAtLeastOneUpstream() {
        assertThatThrownBy(() -> spec().upstreams(List.of()).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("At least one upstream");
    }

    @Test
    @DisplayName("refuses a site whose upstreams are all backups, which would serve nothing")
    void requiresAPrimaryUpstream() {
        assertThatThrownBy(() -> spec()
                .upstreams(List.of(new UpstreamTarget("http", "a", 80, 1, 3, 10, true)))
                .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("primary");
    }

    @Test
    void rejectsDuplicateUpstreams() {
        assertThatThrownBy(() -> spec()
                .upstreams(List.of(UpstreamTarget.of("http", "a", 80), UpstreamTarget.of("http", "a", 80)))
                .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate upstream");
    }

    @Test
    void rejectsDuplicateHeadersRegardlessOfCase() {
        assertThatThrownBy(() -> spec()
                .headers(List.of(
                        new ProxySiteHeader(HeaderDirection.REQUEST, "X-Tenant", "a"),
                        new ProxySiteHeader(HeaderDirection.REQUEST, "x-tenant", "b")))
                .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("SSL without a certificate would render a server block NGINX cannot load")
    void sslRequiresACertificate() {
        assertThatThrownBy(() -> spec().ssl(true, null).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("certificate must be selected");
    }

    @Test
    void httpsRedirectRequiresSsl() {
        assertThatThrownBy(() -> spec().forceHttps(true).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requires SSL");
    }

    private static Builder spec() {
        return new Builder();
    }

    /** Keeps each test to the one field it is about. */
    private static final class Builder {
        private List<UpstreamTarget> upstreams = List.of(UpstreamTarget.of("http", "10.10.10.20", 8080));
        private List<ProxySiteHeader> headers = List.of();
        private boolean sslEnabled;
        private UUID certificateId;
        private boolean forceHttps;

        Builder upstreams(List<UpstreamTarget> value) {
            this.upstreams = value;
            return this;
        }

        Builder headers(List<ProxySiteHeader> value) {
            this.headers = value;
            return this;
        }

        Builder ssl(boolean enabled, UUID certificate) {
            this.sslEnabled = enabled;
            this.certificateId = certificate;
            return this;
        }

        Builder forceHttps(boolean value) {
            this.forceHttps = value;
            return this;
        }

        ProxySiteSpec build() {
            return new ProxySiteSpec("Checkout", DomainName.of("app.example.com"), INSTANCE,
                    TimeWindow.unbounded(), sslEnabled, forceHttps, false, false, certificateId,
                    null, null, upstreams, headers, List.of());
        }
    }
}
