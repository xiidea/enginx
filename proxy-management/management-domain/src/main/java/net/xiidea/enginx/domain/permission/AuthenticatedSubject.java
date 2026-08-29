package net.xiidea.enginx.domain.permission;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The caller, as the token describes them.
 *
 * <p>Group paths come from the access token on every request rather than from a mirrored table.
 * That is what bounds revocation latency to one token lifetime: removing someone from a group in
 * Keycloak takes effect when their next token is issued, with no synchronisation job in between
 * to be stale, stuck or skipped.
 */
public record AuthenticatedSubject(String userSubject, Set<String> groupPaths, Set<GlobalRole> roles) {

    public AuthenticatedSubject {
        groupPaths = groupPaths == null ? Set.of() : Set.copyOf(groupPaths);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean isSuperAdmin() {
        return roles.contains(GlobalRole.SUPER_ADMIN);
    }

    /** The level every scope starts at, from realm roles alone. Null when the roles confer none. */
    public PermissionLevel roleFloor() {
        PermissionLevel floor = null;
        for (GlobalRole role : roles) {
            floor = PermissionLevel.highest(floor, role.implicitLevel());
        }
        return floor;
    }

    /** The highest level any grant may reach for this caller. Null when uncapped. */
    public PermissionLevel roleCeiling() {
        PermissionLevel ceiling = null;
        for (GlobalRole role : roles) {
            PermissionLevel roleCeiling = role.ceiling();
            if (roleCeiling == null) {
                // One uncapped role removes the cap entirely; a cap is a property of holding
                // only capped roles.
                return null;
            }
            ceiling = ceiling == null ? roleCeiling : ceiling.max(roleCeiling);
        }
        return ceiling;
    }

    /** The identifiers a grant may be keyed by for this caller: their subject and their groups. */
    public Set<String> subjectRefs() {
        Set<String> refs = new LinkedHashSet<>();
        if (userSubject != null) {
            refs.add(userSubject);
        }
        refs.addAll(groupPaths);
        return refs;
    }
}
