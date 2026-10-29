package net.xiidea.enginx.domain.proxy;

import net.xiidea.enginx.domain.shared.ValidationException;

import java.util.regex.Pattern;

/**
 * A role that admits its holders to a site signed in through the platform's identity provider.
 *
 * <p>A realm role when {@code client} is null, otherwise a role on that client — Keycloak's
 * {@code realm_access.roles} and {@code resource_access.<client>.roles}. Both names are held to a
 * pattern with no quotes, whitespace or colons: they are rendered into the gate's file and the
 * console writes a client role as {@code client:role}, so a colon in either would be ambiguous.
 */
public record OidcRole(String client, String role) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._@/-]{1,128}$");

    public OidcRole {
        client = client == null || client.isBlank() ? null : client.trim();
        if (client != null && !NAME.matcher(client).matches()) {
            throw new ValidationException("protection.oidc.roles.client",
                    "'" + client + "' is not a valid client id. Use letters, digits, '.', '_', '@', '/' or '-'");
        }
        if (role == null || role.isBlank()) {
            throw new ValidationException("protection.oidc.roles.role", "A role name must not be blank");
        }
        role = role.trim();
        if (!NAME.matcher(role).matches()) {
            throw new ValidationException("protection.oidc.roles.role",
                    "'" + role + "' is not a valid role name. Use letters, digits, '.', '_', '@', '/' or '-'");
        }
    }

    public static OidcRole realm(String role) {
        return new OidcRole(null, role);
    }

    public boolean isClientRole() {
        return client != null;
    }

    /** {@code role}, or {@code client:role}: how the console writes it, and how it sorts. */
    public String display() {
        return client == null ? role : client + ":" + role;
    }
}
