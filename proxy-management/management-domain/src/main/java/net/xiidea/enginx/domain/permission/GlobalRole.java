package net.xiidea.enginx.domain.permission;

import java.util.Locale;
import java.util.Optional;

/**
 * A Keycloak realm role. These establish a floor and a ceiling that apply everywhere; the
 * per-domain detail comes from {@link PermissionGrant}.
 */
public enum GlobalRole {

    /** Everything, everywhere, including permission administration and instance registration. */
    SUPER_ADMIN(PermissionLevel.ADMIN, null),

    /** Implicit MANAGE on every scope. */
    ADMIN(PermissionLevel.MANAGE, null),

    /** No implicit access. Acts only where explicitly granted. */
    OPERATOR(null, null),

    /**
     * A ceiling, not a floor.
     *
     * <p>The role caps this account at READ wherever it has been granted something, and confers
     * nothing on its own. Giving it an implicit READ over every scope would quietly turn every
     * read-only account into a reader of every domain in the platform, which defeats the point
     * of granting access per domain in the first place.
     */
    READ_ONLY(null, PermissionLevel.READ);

    private final PermissionLevel implicitLevel;
    private final PermissionLevel ceiling;

    GlobalRole(PermissionLevel implicitLevel, PermissionLevel ceiling) {
        this.implicitLevel = implicitLevel;
        this.ceiling = ceiling;
    }

    /** The level this role confers on every scope without any grant, or null for none. */
    public PermissionLevel implicitLevel() {
        return implicitLevel;
    }

    /** The highest level this role permits, or null for no cap. */
    public PermissionLevel ceiling() {
        return ceiling;
    }

    public static Optional<GlobalRole> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            // Realms carry roles that mean nothing to this application; ignoring them is correct.
            return Optional.empty();
        }
    }
}
