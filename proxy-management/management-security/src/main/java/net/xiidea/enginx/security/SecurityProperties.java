package net.xiidea.enginx.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param clientId the OIDC client this API accepts tokens for
 * @param roleClientId the client whose <em>client roles</em> count, alongside realm roles: the
 *                     console's own client, which is what people log in through. A deployment can
 *                     then grant the four roles either realm-wide or on that client alone — the
 *                     latter keeps them out of a realm shared with other applications
 * @param corsAllowedOrigins origins permitted to call the API from a browser; the Angular dev
 *                           server in development, the served origin in production
 * @param jwkSetUri where <em>this service</em> fetches signing keys, when that differs from the
 *                  issuer URL that appears inside a token.
 *                  <p>Those two are the same thing only when every party reaches the identity
 *                  provider by one name. In containers they diverge: a browser obtains a token
 *                  from {@code http://localhost:8081}, so that is the issuer stamped into it,
 *                  while this service must reach Keycloak as {@code http://keycloak:8081} on the
 *                  Docker network. Discovery would then refuse to start because the issuer it
 *                  read does not match the location it asked. Setting this splits the two:
 *                  keys are fetched here, and the issuer in the token is still validated against
 *                  {@code issuer-uri}. Leave it unset to use ordinary discovery.
 */
@ConfigurationProperties(prefix = "enginx.security")
public record SecurityProperties(String clientId, String roleClientId, String[] corsAllowedOrigins,
                                 String jwkSetUri) {

    public SecurityProperties {
        clientId = clientId == null ? "enginx-api" : clientId;
        roleClientId = roleClientId == null || roleClientId.isBlank() ? "enginx-frontend" : roleClientId.trim();
        corsAllowedOrigins = corsAllowedOrigins == null ? new String[0] : corsAllowedOrigins.clone();
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? null : jwkSetUri.trim();
    }
}
