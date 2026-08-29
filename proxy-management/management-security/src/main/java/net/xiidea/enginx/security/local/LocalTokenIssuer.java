package net.xiidea.enginx.security.local;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import net.xiidea.enginx.application.identity.AccessTokenIssuer;
import net.xiidea.enginx.domain.identity.LocalUser;
import net.xiidea.enginx.security.AuthProperties;
import net.xiidea.enginx.security.SecurityProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Mints a token for a local account.
 *
 * <p>The claims deliberately mirror Keycloak's — {@code sub}, {@code preferred_username},
 * {@code email}, {@code realm_access.roles}, {@code groups} — so everything downstream is unaware
 * which provider authenticated the caller. The alternative, a second principal type with its own
 * authorization path, would mean every permission decision existing twice and only one of them
 * being exercised by the tests that matter.
 *
 * <p>Symmetric signing, because this service is both the only issuer and the only verifier. A
 * keypair would buy third-party verification that nothing here needs, at the cost of key
 * distribution across replicas.
 */
@Component
public class LocalTokenIssuer implements AccessTokenIssuer {

    private final AuthProperties auth;
    private final SecurityProperties security;
    private final Clock clock;

    public LocalTokenIssuer(AuthProperties auth, SecurityProperties security, Clock clock) {
        this.auth = auth;
        this.security = security;
        this.clock = clock;
    }

    /**
     * @return the signed token and the moment it stops being valid
     */
    @Override
    public IssuedToken issue(LocalUser user) {
        Instant now = clock.instant();
        Instant expiry = now.plus(auth.tokenTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(AuthProperties.LOCAL_ISSUER)
                // Namespaced, so a local subject can never collide with a Keycloak one and inherit
                // a grant written for somebody else.
                .subject(user.subjectRef())
                // The same audience the OIDC path validates, so one rule covers both issuers.
                .audience(security.clientId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .claim("preferred_username", user.username())
                .claim("email", user.email())
                .claim("name", user.displayName())
                .claim("realm_access", Map.of("roles", user.roles().stream().map(Enum::name).toList()))
                .claim("groups", List.copyOf(user.groupPaths()))
                // Read by the console to send the user straight to a password change. Carried in
                // the token rather than fetched, so it cannot be skipped by not asking.
                .claim("must_change_password", user.mustChangePassword())
                .build();

        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(auth.jwtSecret().getBytes(StandardCharsets.UTF_8)));
            return new IssuedToken(jwt.serialize(), expiry, auth.tokenTtl().toSeconds());
        } catch (JOSEException e) {
            // The message can carry key detail, so only the type is reported.
            throw new IllegalStateException("Could not sign a local access token: "
                    + e.getClass().getSimpleName());
        }
    }

}
