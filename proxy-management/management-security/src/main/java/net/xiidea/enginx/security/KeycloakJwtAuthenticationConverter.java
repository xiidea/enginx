package net.xiidea.enginx.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

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
 *
 * <p>A role counts from either place: the realm, or the console's own client
 * ({@code enginx.security.role-client-id}). Neither is required, so a deployment sharing a realm
 * with other applications can keep these roles on the client alone. Client roles of any other
 * client are ignored — a role another application defines is not a grant here, however it is named.
 *
 * <p>{@link #roles} is the one place the set is decided. Spring's authorities, which
 * {@code @PreAuthorize} checks, and the principal's roles, which permission evaluation checks,
 * are both built from it, so the two can never disagree about what a caller is.
 */
@Component
public class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";

    private final String roleClientId;

    public KeycloakJwtAuthenticationConverter(SecurityProperties properties) {
        this.roleClientId = properties.roleClientId();
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = roles(jwt).stream()
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

    /** Realm roles and the console client's roles, together. */
    Set<String> roles(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>(realmRoles(jwt));
        roles.addAll(clientRoles(jwt));
        return roles;
    }

    @SuppressWarnings("unchecked")
    private Set<String> clientRoles(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess == null) {
            return Set.of();
        }
        Object client = resourceAccess.get(roleClientId);
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
    public UserPrincipal toPrincipal(Jwt jwt) {
        Object groupsClaim = jwt.getClaim("groups");
        Set<String> groups = groupsClaim instanceof Collection<?> c
                ? Set.copyOf((Collection<String>) c)
                : Set.of();

        return new UserPrincipal(
                jwt.getSubject(),
                principalName(jwt),
                jwt.getClaimAsString("email"),
                roles(jwt),
                groups);
    }
}
