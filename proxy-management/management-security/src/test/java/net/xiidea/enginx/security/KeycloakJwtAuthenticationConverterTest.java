package net.xiidea.enginx.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A role counts from the realm or from the console's client, and from nowhere else.
 */
class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter = new KeycloakJwtAuthenticationConverter(
            new SecurityProperties("enginx-api", "enginx-frontend", new String[0], null));

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("user-1")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Map<String, Object> realm(String... roles) {
        return Map.of("roles", List.of(roles));
    }

    private static Map<String, Object> client(String client, String... roles) {
        return Map.of(client, Map.of("roles", List.of(roles)));
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void realmRolesAloneAreEnough() {
        Jwt jwt = token(Map.of("realm_access", realm("ADMIN")));

        assertThat(authorities(jwt)).containsExactly("ROLE_ADMIN");
        assertThat(converter.toPrincipal(jwt).roles()).containsExactly("ADMIN");
    }

    @Test
    void consoleClientRolesAloneAreEnough() {
        Jwt jwt = token(Map.of("resource_access", client("enginx-frontend", "SUPER_ADMIN")));

        assertThat(authorities(jwt)).containsExactly("ROLE_SUPER_ADMIN");
        assertThat(converter.toPrincipal(jwt).roles()).containsExactly("SUPER_ADMIN");
    }

    @Test
    void bothSourcesAreUnited() {
        Jwt jwt = token(Map.of(
                "realm_access", realm("READ_ONLY"),
                "resource_access", client("enginx-frontend", "OPERATOR")));

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_READ_ONLY", "ROLE_OPERATOR");
        assertThat(converter.toPrincipal(jwt).roles()).containsExactlyInAnyOrder("READ_ONLY", "OPERATOR");
    }

    @Test
    void anotherClientsRolesGrantNothing() {
        // The API's audience client included: only the console's client is a source of roles.
        Jwt jwt = token(Map.of("resource_access", Map.of(
                "some-other-app", Map.of("roles", List.of("SUPER_ADMIN")),
                "enginx-api", Map.of("roles", List.of("SUPER_ADMIN")))));

        assertThat(authorities(jwt)).isEmpty();
        assertThat(converter.toPrincipal(jwt).roles()).isEmpty();
    }

    @Test
    void theRoleClientIsConfigurable() {
        KeycloakJwtAuthenticationConverter custom = new KeycloakJwtAuthenticationConverter(
                new SecurityProperties("enginx-api", "nginx-console", new String[0], null));
        Jwt jwt = token(Map.of("resource_access", client("nginx-console", "ADMIN")));

        assertThat(custom.toPrincipal(jwt).roles()).containsExactly("ADMIN");
    }

    @Test
    void noRolesAnywhereMeansNoRoles() {
        Jwt jwt = token(Map.of("preferred_username", "nobody"));

        assertThat(authorities(jwt)).isEmpty();
        assertThat(converter.toPrincipal(jwt).roles()).isEmpty();
    }
}
