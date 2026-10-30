package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocationRuleTest {

    private static LocationRule publicAt(String path, LocationMatchType type) {
        return new LocationRule(path, type, 1, true, List.of(), null, LocationProxySettings.none());
    }

    @Test
    @DisplayName("a location without settings is what every location was before")
    void plainLocationsAreUnchanged() {
        LocationRule plain = new LocationRule("/api", LocationMatchType.PREFIX, 1);
        assertThat(plain.publicAccess()).isFalse();
        assertThat(plain.hasOwnUpstreams()).isFalse();
        assertThat(plain.settings().isEmpty()).isTrue();
        assertThat(plain.settings().timeoutsOver(ProxyTimeouts.defaults())).isEqualTo(ProxyTimeouts.defaults());
    }

    @Test
    @DisplayName("the root cannot be public: that is turning protection off")
    void theRootIsNeverPublic() {
        assertThatThrownBy(() -> publicAt("/", LocationMatchType.PREFIX)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> publicAt("/", LocationMatchType.EXACT)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("the platform's own paths, and any prefix of VERIFY's probe path, cannot be public")
    void platformPathsAreNeverPublic() {
        for (String path : List.of("/.", "/.e", "/.enginx-verify-", "/.enginx/auth/x", "/.well-known/enginx/site")) {
            assertThatThrownBy(() -> publicAt(path, LocationMatchType.PREFIX))
                    .describedAs(path).isInstanceOf(ValidationException.class);
        }
        assertThat(publicAt("/health", LocationMatchType.EXACT).publicAccess()).isTrue();
        assertThat(publicAt("/webhooks/", LocationMatchType.PREFIX).publicAccess()).isTrue();
    }

    @Test
    @DisplayName("an own pool is checked as the site's is, and balancing needs one")
    void ownPoolsAreChecked() {
        UpstreamTarget backup = new UpstreamTarget("http", "10.0.0.1", 80, 1, 3, 10, true);
        assertThatThrownBy(() -> new LocationRule("/api", LocationMatchType.PREFIX, 1, false, List.of(backup), null, null))
                .isInstanceOf(ValidationException.class);
        UpstreamTarget one = UpstreamTarget.of("http", "10.0.0.1", 80);
        assertThatThrownBy(() -> new LocationRule("/api", LocationMatchType.PREFIX, 1, false, List.of(one, one), null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new LocationRule("/api", LocationMatchType.PREFIX, 1, false, List.of(),
                LoadBalancingMethod.LEAST_CONN, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("settings are bounded as the site's are, and headers are request headers")
    void settingsAreBounded() {
        assertThatThrownBy(() -> new LocationProxySettings(0, null, null, null, null, List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new LocationProxySettings(null, null, null, -1L, null, List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new LocationProxySettings(null, null, null, null, null,
                List.of(new ProxySiteHeader(HeaderDirection.RESPONSE, "X-Frame-Options", "DENY"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a location's header replaces the site's of the same name, and adds to the rest")
    void headersMerge() {
        LocationProxySettings settings = new LocationProxySettings(null, null, null, null, null,
                List.of(new ProxySiteHeader(HeaderDirection.REQUEST, "x-tenant", "api")));
        List<ProxySiteHeader> merged = settings.requestHeadersOver(List.of(
                new ProxySiteHeader(HeaderDirection.REQUEST, "X-Tenant", "web"),
                new ProxySiteHeader(HeaderDirection.REQUEST, "X-Env", "prod"),
                new ProxySiteHeader(HeaderDirection.RESPONSE, "X-Frame-Options", "DENY")));
        assertThat(merged).extracting(ProxySiteHeader::name, ProxySiteHeader::value)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("X-Env", "prod"),
                        org.assertj.core.groups.Tuple.tuple("x-tenant", "api"));
    }
}
