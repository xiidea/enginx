package net.xiidea.enginx.domain.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainNameTest {

    @Test
    @DisplayName("normalises case and a trailing dot so the database never needs a case-insensitive type")
    void normalises() {
        assertThat(DomainName.of("  APP.Example.COM. ").value()).isEqualTo("app.example.com");
    }

    @Test
    @DisplayName("reverses labels for indexed wildcard matching")
    void reverses() {
        assertThat(DomainName.of("api.staging.example.com").reversed()).isEqualTo("com.example.staging.api");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "app.example.com; return 200",   // directive termination
            "app.example.com\nserver_name x", // newline injection
            "app.example.com{",               // block opening
            "app example.com",                // whitespace
            "-leading.example.com",           // label may not start with a hyphen
            "trailing-.example.com",
            "localhost",                      // not fully qualified
            "http://app.example.com",         // a URL, not a name
            "app..example.com"
    })
    @DisplayName("rejects anything that could terminate or extend an NGINX directive")
    void rejectsMalformed(String candidate) {
        assertThatThrownBy(() -> DomainName.of(candidate)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> DomainName.of("   ")).isInstanceOf(ValidationException.class);
    }
}
