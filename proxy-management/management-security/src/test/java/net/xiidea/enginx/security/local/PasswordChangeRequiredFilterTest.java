package net.xiidea.enginx.security.local;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The confinement applied to an account that has not yet replaced the password it was issued.
 *
 * <p>These cases are the reason the check lives on the server. A console can be told to show a
 * password form first; a bearer token cannot be told to only be used by a console.
 */
class PasswordChangeRequiredFilterTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String OWN_PASSWORD_PATH = "/api/v1/local-users/" + ID + "/password";

    private final PasswordChangeRequiredFilter filter =
            new PasswordChangeRequiredFilter(new ObjectMapper());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a caller with the flag set is refused everything else")
    void confinedCallerIsRefused() throws Exception {
        authenticateWithFlag(true, "local:" + ID);

        MockHttpServletResponse response = run("GET", "/api/v1/proxy-sites");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("password-change-required");
    }

    @Test
    @DisplayName("a caller with the flag set may still change its own password")
    void ownPasswordChangeIsAllowed() throws Exception {
        authenticateWithFlag(true, "local:" + ID);

        assertThat(run("PUT", OWN_PASSWORD_PATH).getStatus()).isEqualTo(200);
    }

    /**
     * The escalation this filter would otherwise create.
     *
     * <p>Whoever read the bootstrap password out of a deployment manifest holds a token with this
     * flag. If the allowance were "any password change" rather than "your own", that token could
     * take over the account of anybody else — including an administrator who has already rotated.
     */
    @Test
    @DisplayName("the allowance is for this caller's own password, not anybody's")
    void somebodyElsesPasswordIsStillRefused() throws Exception {
        authenticateWithFlag(true, "local:" + ID);

        MockHttpServletResponse response =
                run("PUT", "/api/v1/local-users/" + UUID.randomUUID() + "/password");

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("an ordinary caller is untouched")
    void unflaggedCallerPassesThrough() throws Exception {
        authenticateWithFlag(false, "local:" + ID);

        assertThat(run("GET", "/api/v1/proxy-sites").getStatus()).isEqualTo(200);
    }

    /**
     * A federated token carries no such claim, and a provider that started sending one must not be
     * able to lock the console out of the platform.
     */
    @Test
    @DisplayName("a request with no authentication is left to the chain that rejects it")
    void anonymousRequestPassesThrough() throws Exception {
        assertThat(run("GET", "/api/v1/proxy-sites").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse run(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static void authenticateWithFlag(boolean mustChange, String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .claim(PasswordChangeRequiredFilter.CLAIM, mustChange)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claims(claims -> claims.putAll(Map.of()))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }
}
