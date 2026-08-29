package net.xiidea.enginx.security;

import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.security.local.LocalTokenIssuer;
import net.xiidea.enginx.domain.permission.GlobalRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The token this platform mints, against the decoder that will actually validate it.
 *
 * <p>This is the seam the whole feature rests on. If a locally issued token were not accepted by
 * the same decoder that accepts a federated one, every authorization decision downstream would need
 * a second code path — and the one exercised by the tests would not be the one used in production.
 */
class LocalTokenRoundTripTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough-for-hs256";
    private static final String CLIENT_ID = "enginx-api";
    /**
     * Fixed for determinism, but anchored to now rather than to a literal instant.
     *
     * <p>The decoder validates {@code exp} against the system clock, which no test can fix. A
     * literal instant therefore produces a token whose validity depends on what time of day the
     * suite runs — green all morning, and expired from the moment the wall clock passes the TTL.
     */
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    private static AuthProperties auth(String secret) {
        return new AuthProperties(false, true, secret, Duration.ofHours(8), null, null);
    }

    private static SecurityProperties security() {
        return new SecurityProperties(CLIENT_ID, new String[0], null);
    }

    private static LocalTokenIssuer issuer(String secret) {
        return new LocalTokenIssuer(auth(secret), security(), CLOCK);
    }

    /** The decoder the resource server would build for a local-only deployment. */
    private static JwtDecoder decoder(String secret) {
        JwtDecoderConfiguration config = new JwtDecoderConfiguration(security(), auth(secret));
        return config.jwtDecoder(config.jwtDecodersByIssuer(""));
    }

    private static LocalUser user() {
        return LocalUser.create(UUID.fromString("11111111-2222-3333-4444-555555555555"), "ada",
                "$2a$10$hash", "ada@example.com", "Ada", Set.of(GlobalRole.ADMIN),
                Set.of("/platform/operators"), false, "root", CLOCK.instant());
    }

    @Test
    @DisplayName("a locally issued token is accepted, and carries the claims Keycloak would")
    void roundTrips() {
        Jwt decoded = decoder(SECRET).decode(issuer(SECRET).issue(user()).token());

        // The same shape a federated token has, which is what lets everything downstream stay
        // unaware of which provider authenticated the caller.
        assertThat(decoded.getSubject()).isEqualTo("local:11111111-2222-3333-4444-555555555555");
        assertThat(decoded.getClaimAsString("preferred_username")).isEqualTo("ada");
        assertThat(decoded.getClaimAsString("email")).isEqualTo("ada@example.com");
        assertThat(decoded.getClaimAsStringList("groups")).containsExactly("/platform/operators");
        assertThat(decoded.getAudience()).containsExactly(CLIENT_ID);

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) decoded.getClaimAsMap("realm_access").get("roles");
        assertThat(roles).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("the subject is namespaced, so it cannot collide with a Keycloak subject")
    void subjectIsNamespaced() {
        Jwt decoded = decoder(SECRET).decode(issuer(SECRET).issue(user()).token());

        assertThat(decoded.getSubject()).startsWith(LocalUser.SUBJECT_PREFIX);
        // A grant written for the bare UUID must not be reachable by this token.
        assertThat(decoded.getSubject()).isNotEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    @DisplayName("a token signed with another secret is refused")
    void wrongSecretIsRefused() {
        String forged = issuer("a-different-secret-also-long-enough-for-hs256!!").issue(user()).token();

        assertThatThrownBy(() -> decoder(SECRET).decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a tampered token is refused")
    void tamperedTokenIsRefused() {
        String token = issuer(SECRET).issue(user()).token();
        String[] parts = token.split("\\.");

        // Re-encode the payload with SUPER_ADMIN. The signature no longer covers it.
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        String elevated = payload.replace("\"ADMIN\"", "\"SUPER_ADMIN\"");
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(elevated.getBytes()) + "." + parts[2];

        assertThatThrownBy(() -> decoder(SECRET).decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("the platform refuses to start with no way to authenticate anybody")
    void bothProvidersOffIsRefused() {
        AuthProperties nothingEnabled = new AuthProperties(false, false, null, null, null, null);

        assertThatThrownBy(nothingEnabled::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nobody could authenticate");
    }

    @Test
    @DisplayName("local authentication without a signing secret is refused at startup")
    void localWithoutSecretIsRefused() {
        assertThatThrownBy(() -> new AuthProperties(false, true, null, null, null, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secret");

        // Too short to be an HS256 key. Nimbus would refuse it later with a far less useful message.
        assertThatThrownBy(() -> new AuthProperties(false, true, "short", null, null, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("a token for another audience is refused, even with a valid signature")
    void wrongAudienceIsRefused() {
        LocalTokenIssuer other = new LocalTokenIssuer(auth(SECRET),
                new SecurityProperties("some-other-client", new String[0], null), CLOCK);

        assertThatThrownBy(() -> decoder(SECRET).decode(other.issue(user()).token()))
                .isInstanceOf(JwtException.class);
    }
}
