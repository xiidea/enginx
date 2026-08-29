package net.xiidea.enginx.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps a Keycloak token onto Spring Security authorities.
 *
 * <p>Keycloak nests realm roles under {@code realm_access.roles} and client roles under
 * {@code resource_access.<client>.roles}; neither is where Spring looks by default, which is why
 * this converter exists rather than a scope prefix configuration.
 */
public class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";

    private final String clientId;

    public KeycloakJwtAuthenticationConverter(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(realmRoles(jwt));
        roles.addAll(clientRoles(jwt));

        Collection<GrantedAuthority> authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();

        return new JwtAuthenticationToken(jwt, authorities, principalName(jwt));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return Set.of();
        }
        Object roles = realmAccess.get("roles");
        return roles instanceof Collection<?> c ? Set.copyOf((Collection<String>) c) : Set.of();
    }

    @SuppressWarnings("unchecked")
    private Set<String> clientRoles(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null || clientId == null) {
            return Set.of();
        }
        Object client = resourceAccess.get(clientId);
        if (!(client instanceof Map<?, ?> clientMap)) {
            return Set.of();
        }
        Object roles = clientMap.get("roles");
        return roles instanceof Collection<?> c ? Set.copyOf((Collection<String>) c) : Set.of();
    }

    private static String principalName(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        return preferredUsername != null ? preferredUsername : jwt.getSubject();
    }

    /** Projects the token onto the application's own principal type. */
    @SuppressWarnings("unchecked")
    public static UserPrincipal toPrincipal(Jwt jwt) {
        Object groupsClaim = jwt.getClaim("groups");
        Set<String> groups = groupsClaim instanceof Collection<?> c
                ? Set.copyOf((Collection<String>) c)
                : Set.of();

        return new UserPrincipal(
                jwt.getSubject(),
                principalName(jwt),
                jwt.getClaimAsString("email"),
                realmRoles(jwt),
                groups);
    }
}
