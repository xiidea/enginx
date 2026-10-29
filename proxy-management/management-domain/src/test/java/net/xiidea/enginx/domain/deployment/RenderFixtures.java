package net.xiidea.enginx.domain.deployment;

import net.xiidea.enginx.domain.proxy.AdminState;
import net.xiidea.enginx.domain.proxy.AllowedEmail;
import net.xiidea.enginx.domain.proxy.EmailOtpPolicy;
import net.xiidea.enginx.domain.proxy.BasicAuthUser;
import net.xiidea.enginx.domain.proxy.HeaderDirection;
import net.xiidea.enginx.domain.proxy.LoadBalancingMethod;
import net.xiidea.enginx.domain.proxy.LocationMatchType;
import net.xiidea.enginx.domain.proxy.LocationRule;
import net.xiidea.enginx.domain.proxy.ProxySite;
import net.xiidea.enginx.domain.proxy.ProxySiteHeader;
import net.xiidea.enginx.domain.proxy.ProxySiteSpec;
import net.xiidea.enginx.domain.proxy.ProxyTimeouts;
import net.xiidea.enginx.domain.proxy.SiteProtection;
import net.xiidea.enginx.domain.proxy.UpstreamTarget;
import net.xiidea.enginx.domain.shared.DomainName;
import net.xiidea.enginx.domain.shared.TimeWindow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Deterministic fixtures: fixed ids and a fixed clock, so golden output is reproducible. */
final class RenderFixtures {

    static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    static final UUID INSTANCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID BUNDLE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID CERTIFICATE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** SHA-512-crypt of "secret" and "other", fixed salts, so golden output is reproducible. */
    static final String SECRET_HASH =
            "$6$fixturesalt0001$nTLkJQUNn08kFVHCPBxOSfCbqF3lA60ZVOdyzJeqdQv4MeAjTOPRztNt1OcbBALrB.AcE2mRt.mh8cHpEl2UD1";
    static final String OTHER_HASH =
            "$6$fixturesalt0002$SVJ/Qcuj5pOFgDaMim7YMeD0zspwX5SMLItbweCxpQAE8vOisbTGKkZoa6BxlnMLKacxFEmmhFpz8g7P7athb.";

    static final String GATE_KEY = "Z2F0ZS1rZXktZm9yLXRoZS1yZW5kZXItZml4dHVyZXM=";

    static final GateMaterialProvider TEST_GATE = new GateMaterialProvider() {
        @Override
        public Optional<String> keyFor(UUID siteId) {
            return Optional.of(GATE_KEY);
        }

        @Override
        public Optional<String> relayUrl() {
            return Optional.of("https://enginx.example.com/api/v1/agents/gate/otp-mail");
        }

        @Override
        public Optional<GateOidcProvider> oidcProvider() {
            return Optional.of(new GateOidcProvider("https://id.example.com/realms/staff",
                    "http://keycloak:8080/realms/staff", "enginx-sites", null, null));
        }
    };

    private RenderFixtures() {
    }

    static final CertificateMaterialProvider NO_CERTIFICATES = certificateId -> Optional.empty();

    static final CertificateMaterialProvider TEST_CERTIFICATE = certificateId ->
            Optional.of(new RenderedCertificate(certificateId,
                    "-----BEGIN CERTIFICATE-----\nfullchain\n-----END CERTIFICATE-----\n",
                    "-----BEGIN PRIVATE KEY-----\nprivate\n-----END PRIVATE KEY-----\n"));

    static ProxySite site(UUID id, String domain, ProxySiteSpec spec) {
        return ProxySite.rehydrate(id, spec, AdminState.ENABLED,
                net.xiidea.enginx.domain.proxy.SiteStatus.ACTIVE, "ada", NOW, "ada", NOW, 0L);
    }

    static Builder spec(String domain) {
        return new Builder(domain);
    }

    static final class Builder {
        private final String domain;
        private List<UpstreamTarget> upstreams = List.of(UpstreamTarget.of("http", "10.10.10.20", 8080));
        private List<ProxySiteHeader> headers = List.of();
        private List<LocationRule> locations = List.of();
        private LoadBalancingMethod lb = LoadBalancingMethod.ROUND_ROBIN;
        private ProxyTimeouts timeouts = ProxyTimeouts.defaults();
        private boolean ssl;
        private boolean forceHttps;
        private boolean hsts;
        private boolean websocket;
        private TimeWindow window = TimeWindow.unbounded();
        private SiteProtection protection;

        Builder(String domain) {
            this.domain = domain;
        }

        Builder upstreams(UpstreamTarget... values) {
            this.upstreams = List.of(values);
            return this;
        }

        Builder headers(ProxySiteHeader... values) {
            this.headers = List.of(values);
            return this;
        }

        Builder locations(LocationRule... values) {
            this.locations = List.of(values);
            return this;
        }

        Builder loadBalancing(LoadBalancingMethod value) {
            this.lb = value;
            return this;
        }

        Builder timeouts(ProxyTimeouts value) {
            this.timeouts = value;
            return this;
        }

        Builder ssl(boolean forceHttpsToo, boolean hstsToo) {
            this.ssl = true;
            this.forceHttps = forceHttpsToo;
            this.hsts = hstsToo;
            return this;
        }

        Builder websocket() {
            this.websocket = true;
            return this;
        }

        Builder basicAuth(BasicAuthUser... users) {
            this.protection = SiteProtection.basic(List.of(users));
            return this;
        }

        Builder basicAuthOnLoginPage(BasicAuthUser... users) {
            this.protection = SiteProtection.basicWithLoginPage(List.of(users),
                    new net.xiidea.enginx.domain.proxy.BasicLoginPage(java.time.Duration.ofHours(8)));
            return this;
        }

        Builder emailOtp(String... allowed) {
            this.protection = net.xiidea.enginx.domain.proxy.SiteProtection.emailOtp(new EmailOtpPolicy(
                    java.util.Arrays.stream(allowed).map(AllowedEmail::new).toList(), null));
            return this;
        }

        Builder oidc(net.xiidea.enginx.domain.proxy.OidcAccessPolicy policy) {
            this.protection = net.xiidea.enginx.domain.proxy.SiteProtection.oidc(policy);
            return this;
        }

        Builder expiring(Instant at) {
            this.window = TimeWindow.until(at);
            return this;
        }

        ProxySiteSpec build() {
            return new ProxySiteSpec("Site " + domain, DomainName.of(domain), INSTANCE, window,
                    ssl, forceHttps, hsts, websocket, ssl ? CERTIFICATE : null,
                    lb, timeouts, upstreams, headers, locations, protection);
        }

        ProxySite site(String id) {
            return RenderFixtures.site(UUID.fromString(id), domain, build());
        }
    }

    static ProxySiteHeader requestHeader(String name, String value) {
        return new ProxySiteHeader(HeaderDirection.REQUEST, name, value);
    }

    static ProxySiteHeader responseHeader(String name, String value) {
        return new ProxySiteHeader(HeaderDirection.RESPONSE, name, value);
    }

    static LocationRule location(String path, LocationMatchType type, int order) {
        return new LocationRule(path, type, order);
    }
}
