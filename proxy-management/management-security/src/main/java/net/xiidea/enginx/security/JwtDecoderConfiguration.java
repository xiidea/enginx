package net.xiidea.enginx.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a decoder per enabled issuer, and routes a token to the right one.
 *
 * <p>Two issuers can be live at once — the configured OIDC provider and this service itself — and
 * a token names which minted it. Routing on the {@code iss} claim rather than trying each decoder
 * in turn matters for a reason beyond tidiness: trying them in turn means a token from one issuer
 * produces a signature failure against the other on every request, and those failures are
 * indistinguishable in a log from a real forgery attempt.
 *
 * <p>Every decoder validates audience, so a token minted for another application in the same realm
 * is refused whichever issuer produced it.
 */
@Configuration(proxyBeanMethods = false)
public class JwtDecoderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderConfiguration.class);

    private final SecurityProperties security;
    private final AuthProperties auth;

    public JwtDecoderConfiguration(SecurityProperties security, AuthProperties auth) {
        this.security = security;
        this.auth = auth;
        auth.validate();
    }

    /**
     * Decoders keyed by the issuer they accept.
     *
     * <p>Exposed as a map rather than a single {@code JwtDecoder} because
     * {@link IssuerRoutingJwtDecoder} needs to choose, and because an empty map would mean a
     * misconfiguration that {@link AuthProperties#validate()} has already refused to start on.
     */
    @Bean
    Map<String, JwtDecoder> jwtDecodersByIssuer(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String oidcIssuer) {

        Map<String, JwtDecoder> decoders = new LinkedHashMap<>();

        if (auth.oidcEnabled()) {
            if (oidcIssuer == null || oidcIssuer.isBlank()) {
                throw new IllegalStateException(
                        "enginx.auth.oidc-enabled is true but "
                                + "spring.security.oauth2.resourceserver.jwt.issuer-uri is not set.");
            }
            decoders.put(oidcIssuer, oidcDecoder(oidcIssuer));
            log.info("Accepting OIDC tokens issued by {}", oidcIssuer);
        }

        if (auth.localEnabled()) {
            decoders.put(AuthProperties.LOCAL_ISSUER, localDecoder());
            log.info("Accepting locally issued tokens ({})", AuthProperties.LOCAL_ISSUER);
        }

        return Map.copyOf(decoders);
    }

    @Bean
    JwtDecoder jwtDecoder(Map<String, JwtDecoder> jwtDecodersByIssuer) {
        return new IssuerRoutingJwtDecoder(jwtDecodersByIssuer);
    }

    /**
     * Adds audience validation on top of the standard issuer and expiry checks. Without it, any
     * token from the same realm would be accepted here, including one minted for another app.
     *
     * <p>When {@code enginx.security.jwk-set-uri} is set, keys are fetched from there instead of
     * through discovery. The issuer inside the token is still validated against {@code issuerUri}
     * either way, so splitting the two loosens nothing: it only lets this service reach the
     * identity provider by a different name than the browser does.
     */
    private JwtDecoder oidcDecoder(String issuerUri) {
        NimbusJwtDecoder decoder = security.jwkSetUri() == null
                ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri)
                : NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri()).build();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(security.clientId())));
        return decoder;
    }

    /**
     * Symmetric, because this service is both the only issuer and the only verifier of these
     * tokens. Pinning the algorithm matters: without it a token could nominate its own, and
     * {@code alg: none} is the oldest bug in this area.
     */
    private JwtDecoder localDecoder() {
        SecretKeySpec key = new SecretKeySpec(
                auth.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(key)
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(AuthProperties.LOCAL_ISSUER),
                new AudienceValidator(security.clientId())));
        return decoder;
    }

    /**
     * Chooses a decoder by the token's {@code iss} claim.
     *
     * <p>The claim is read without verifying the signature, which is safe only because it is used
     * to pick a verifier and never to make a decision: an attacker naming an issuer they do not
     * hold the key for merely gets their forgery checked against that issuer's key and rejected.
     */
    static final class IssuerRoutingJwtDecoder implements JwtDecoder {

        private final Map<String, JwtDecoder> byIssuer;

        IssuerRoutingJwtDecoder(Map<String, JwtDecoder> byIssuer) {
            this.byIssuer = byIssuer;
        }

        @Override
        public Jwt decode(String token) {
            if (byIssuer.size() == 1) {
                return byIssuer.values().iterator().next().decode(token);
            }

            String issuer = unverifiedIssuer(token);
            JwtDecoder decoder = issuer == null ? null : byIssuer.get(issuer);
            if (decoder == null) {
                throw new org.springframework.security.oauth2.jwt.BadJwtException(
                        "No configured issuer accepts this token");
            }
            return decoder.decode(token);
        }

        private static String unverifiedIssuer(String token) {
            try {
                return com.nimbusds.jwt.JWTParser.parse(token).getJWTClaimsSet()
                        .getStringClaim(JwtClaimNames.ISS);
            } catch (Exception e) {
                // Malformed enough that no decoder would accept it either.
                return null;
            }
        }
    }
}
