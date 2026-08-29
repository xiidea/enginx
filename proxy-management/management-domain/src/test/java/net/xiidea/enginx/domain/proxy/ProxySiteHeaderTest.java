package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxySiteHeaderTest {

    @Test
    void acceptsAnOrdinaryHeader() {
        ProxySiteHeader header = new ProxySiteHeader(HeaderDirection.REQUEST, "X-Tenant-Id", "acme");
        assertThat(header.name()).isEqualTo("X-Tenant-Id");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "value\nproxy_pass http://evil",   // append a directive
            "value; add_header X 1",           // terminate the directive
            "value{",                          // open a block
            "value\"",                         // break out of the quoted string
            "value\\",                         // escape the closing quote
            "$http_authorization"              // interpolate a variable we never meant to expose
    })
    @DisplayName("rejects every character that could escape the generated directive")
    void rejectsInjection(String value) {
        assertThatThrownBy(() -> new ProxySiteHeader(HeaderDirection.REQUEST, "X-Test", value))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsInvalidHeaderNames() {
        assertThatThrownBy(() -> new ProxySiteHeader(HeaderDirection.RESPONSE, "X Bad Name", "v"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsOverlongValues() {
        assertThatThrownBy(() -> new ProxySiteHeader(HeaderDirection.REQUEST, "X-Long", "a".repeat(1025)))
                .isInstanceOf(ValidationException.class);
    }
}
