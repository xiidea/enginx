package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamTargetTest {

    @Test
    void acceptsHostnamesIpv4AndBracketedIpv6() {
        assertThat(UpstreamTarget.of("http", "backend", 8080).authority()).isEqualTo("backend:8080");
        assertThat(UpstreamTarget.of("http", "10.10.10.20", 8080).authority()).isEqualTo("10.10.10.20:8080");
        assertThat(UpstreamTarget.of("https", "[::1]", 8443).authority()).isEqualTo("[::1]:8443");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.10.10.20:8080",      // a whole URL smuggled into the host field
            "10.10.10.20/../admin",         // path traversal
            "10.10.10.20; return 200",      // directive termination
            "10.10.10.20\nproxy_pass evil", // newline injection
            "backend:8080",                 // port belongs in its own field
            "999.1.1.1"
    })
    @DisplayName("refuses a URL or anything else that would reach proxy_pass whole")
    void rejectsUrlsAndInjection(String host) {
        assertThatThrownBy(() -> UpstreamTarget.of("http", host, 8080))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsSchemesOtherThanHttpAndHttps() {
        assertThatThrownBy(() -> UpstreamTarget.of("file", "backend", 80))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsPortsOutsideTheValidRange() {
        assertThatThrownBy(() -> UpstreamTarget.of("http", "backend", 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> UpstreamTarget.of("http", "backend", 70000)).isInstanceOf(ValidationException.class);
    }

    @Test
    void defaultsTheSchemeToHttp() {
        assertThat(new UpstreamTarget(null, "backend", 80, 1, 3, 10, false).scheme()).isEqualTo("http");
    }
}
