package net.xiidea.enginx.security;

import java.util.Set;

/**
 * The authenticated caller, projected from the OIDC token.
 *
 * <p>Group membership is read from the token on every request rather than from a mirrored table,
 * so removing a user from a Keycloak group takes effect within one access-token lifetime rather
 * than whenever a background sync happens to run (architecture risk R6).
 */
public record UserPrincipal(
        String subject,
        String username,
        String email,
        Set<String> roles,
        Set<String> groups) {

    public UserPrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
