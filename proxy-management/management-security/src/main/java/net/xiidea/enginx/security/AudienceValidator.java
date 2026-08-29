package net.xiidea.enginx.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Rejects tokens minted for a different client.
 *
 * <p>Without this, any valid token from the same realm would be accepted here, including one
 * issued to an unrelated application that happens to share the identity provider.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token", "The token was not issued for this API", null);

    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();
        if (audiences != null && audiences.contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        // Keycloak omits `aud` unless an audience mapper is configured, so fall back to the
        // authorized party claim rather than locking every deployment out by default.
        String azp = token.getClaimAsString("azp");
        if (expectedAudience.equals(azp)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(ERROR);
    }
}
